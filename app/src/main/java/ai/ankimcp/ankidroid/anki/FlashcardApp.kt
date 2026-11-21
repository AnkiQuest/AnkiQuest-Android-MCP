// SPDX-License-Identifier: AGPL-3.0-or-later

package ai.ankimcp.ankidroid.anki

import ai.ankimcp.ankidroid.settings.KeyValueStore

/**
 * The flashcard apps whose AnkiDroid provider API we talk to (D65). A closed list: an app is never
 * used because it declares a provider, only because it is named here and the user picked it.
 * AnkiQuest is a fork of AnkiDroid with the same provider code under its own package.
 */
enum class FlashcardApp(val packageName: String, val authority: String, val permission: String, val displayName: String) {
    ANKIDROID(
        "com.ichi2.anki",
        "com.ichi2.anki.flashcards",
        "com.ichi2.anki.permission.READ_WRITE_DATABASE",
        "AnkiDroid",
    ),
    ANKIQUEST(
        "org.ankiquest.anki",
        "org.ankiquest.anki.flashcards",
        "org.ankiquest.anki.permission.READ_WRITE_DATABASE",
        "AnkiQuest",
    ),

    /**
     * The package released AnkiQuest builds install as today, trusted like [ANKIQUEST] (D67).
     * Remove once its users have moved to `org.ankiquest.anki` (AnkiQuest-Android#420).
     */
    ANKIQUEST_RELEASED(
        "com.ichi2.anki.quest",
        "com.ichi2.anki.quest.flashcards",
        "com.ichi2.anki.quest.permission.READ_WRITE_DATABASE",
        "AnkiQuest",
    ),
    ;

    companion object {
        /** Used without asking, as before; every other app needs the user's choice. */
        val DEFAULT = ANKIDROID
    }
}

/** Which flashcard app is used, if any (D65). */
sealed interface FlashcardAppState {
    /**
     * Installed apps that hold their own authority, AnkiDroid only when signed with its release
     * key (D66): what the picker lists.
     */
    val candidates: List<FlashcardApp>

    data class Ready(val app: FlashcardApp, override val candidates: List<FlashcardApp>) : FlashcardAppState

    /** No trusted choice: the user must pick one of [candidates]. */
    data class NeedsChoice(override val candidates: List<FlashcardApp>) : FlashcardAppState

    data object NoneInstalled : FlashcardAppState {
        override val candidates: List<FlashcardApp> = emptyList()
    }
}

/**
 * The user's choice and its pinned signing certificates, in [store]. A pin is the set of
 * certificate digests seen when the app was picked. AnkiDroid has none: it is checked against its
 * built-in release key instead (D66).
 */
class FlashcardAppChoice(private val store: KeyValueStore) {
    fun chosen(): FlashcardApp? = store.getString(KEY_CHOSEN)?.let { name -> FlashcardApp.entries.find { it.name == name } }

    fun pin(app: FlashcardApp): Set<String>? =
        store.getString(pinKey(app))?.split(DIGEST_SEPARATOR)?.filter { it.isNotEmpty() }?.toSet()

    /** Makes [app] the choice with [pin]; null stores no pin. */
    fun save(app: FlashcardApp, pin: Set<String>?) {
        store.edit(mapOf(KEY_CHOSEN to app.name, pinKey(app) to pin?.sorted()?.joinToString(DIGEST_SEPARATOR)))
    }

    /** Drops [app] as the choice together with its pin. */
    fun forget(app: FlashcardApp) {
        store.edit(mapOf(KEY_CHOSEN to null, pinKey(app) to null))
    }

    private fun pinKey(app: FlashcardApp) = "$KEY_PIN_PREFIX${app.name}"

    companion object {
        const val PREFS_NAME = "flashcard_app"
        private const val KEY_CHOSEN = "chosen"
        private const val KEY_PIN_PREFIX = "pin_"
        private const val DIGEST_SEPARATOR = ","
    }
}

/**
 * Decides which [FlashcardApp] to use (D65). Rules, checked on every [resolve]:
 * - A candidate is installed and is the package behind its own authority, so an app squatting
 *   the authority while the real package is missing is never used. AnkiDroid must also be signed
 *   with its release key, or [build] must be debuggable ([AnkiDroidCheck], D66).
 * - The user's choice stands while it is a candidate and, for apps other than AnkiDroid, its
 *   certificates still contain the pin; otherwise the choice and its pin are dropped and the
 *   default applies.
 * - AnkiDroid (the default) is used without asking, as before.
 * - Any other app is used only after [choose].
 */
class FlashcardAppResolver(
    private val installed: InstalledApps,
    private val choice: FlashcardAppChoice,
    build: AppBuild,
) {
    private val ankiDroid = AnkiDroidCheck(installed, build)

    /** The app the last [resolve] returned as ready; read by the provider without new lookups. */
    @Volatile
    var current: FlashcardApp? = null
        private set

    @Synchronized
    fun resolve(): FlashcardAppState {
        val candidates = candidates()
        val state = stateOf(candidates)
        current = (state as? FlashcardAppState.Ready)?.app
        return state
    }

    /** Why AnkiDroid is or is not used (D66); says why when no app is ready. */
    fun ankiDroidTrust(): ProviderTrust = ankiDroid.trust()

    /**
     * Makes [app] the user's choice, pinning its certificates now unless it is AnkiDroid, which
     * its release key identifies. False if it is not a candidate.
     */
    @Synchronized
    fun choose(app: FlashcardApp): Boolean {
        if (app !in candidates()) {
            return false
        }
        val pin = when (app) {
            FlashcardApp.ANKIDROID -> null
            FlashcardApp.ANKIQUEST, FlashcardApp.ANKIQUEST_RELEASED ->
                installed.signerDigests(app.packageName) ?: return false
        }
        choice.save(app, pin)
        return true
    }

    private fun stateOf(candidates: List<FlashcardApp>): FlashcardAppState {
        // The user's choice first.
        val chosen = choice.chosen()
        if (chosen != null) {
            if (chosen in candidates && pinMatches(chosen)) {
                return FlashcardAppState.Ready(chosen, candidates)
            }
            choice.forget(chosen)
        }

        // Then the default, which being a candidate already proves genuine.
        val default = FlashcardApp.DEFAULT
        if (default in candidates) {
            return FlashcardAppState.Ready(default, candidates)
        }

        if (candidates.isEmpty()) {
            return FlashcardAppState.NoneInstalled
        }
        return FlashcardAppState.NeedsChoice(candidates)
    }

    private fun candidates(): List<FlashcardApp> = FlashcardApp.entries.filter(::isCandidate)

    private fun isCandidate(app: FlashcardApp): Boolean = when (app) {
        // Authority holder and release key, checked together (D66).
        FlashcardApp.ANKIDROID -> ankiDroid.trust() == ProviderTrust.Trusted
        FlashcardApp.ANKIQUEST, FlashcardApp.ANKIQUEST_RELEASED ->
            installed.signerDigests(app.packageName) != null && installed.authorityHolder(app.authority) == app.packageName
    }

    /** AnkiDroid has no pin: its release key was checked as a candidate. */
    private fun pinMatches(app: FlashcardApp): Boolean {
        if (app == FlashcardApp.ANKIDROID) {
            return true
        }
        val pin = choice.pin(app) ?: return false
        val digests = installed.signerDigests(app.packageName) ?: return false
        return pin.isNotEmpty() && digests.containsAll(pin)
    }
}
