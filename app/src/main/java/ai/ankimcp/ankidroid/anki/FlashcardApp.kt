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
        "dev.hilll.anki",
        "dev.hilll.anki.flashcards",
        "dev.hilll.anki.permission.READ_WRITE_DATABASE",
        "AnkiQuest",
    ),
    ;

    companion object {
        /** Used without asking, as before; every other app needs the user's choice. */
        val DEFAULT = ANKIDROID
    }
}

/** What PackageManager says about a package; the Android side is `AndroidInstalledApps`. */
interface InstalledApps {
    /** SHA-256 hex of the package's signing certificates (with rotation history), or null if not installed. */
    fun signerDigests(packageName: String): Set<String>?

    /** The package that holds [authority], or null if none does. */
    fun authorityHolder(authority: String): String?
}

/** Which flashcard app is used, if any (D65). */
sealed interface FlashcardAppState {
    /** Installed apps that hold their own authority: what the picker lists. */
    val candidates: List<FlashcardApp>

    data class Ready(val app: FlashcardApp, override val candidates: List<FlashcardApp>) : FlashcardAppState

    /** No trusted choice: the user must pick one of [candidates]. */
    data class NeedsChoice(override val candidates: List<FlashcardApp>) : FlashcardAppState

    data object NoneInstalled : FlashcardAppState {
        override val candidates: List<FlashcardApp> = emptyList()
    }
}

/**
 * The user's choice and each app's pinned signing certificates, in [store]. A pin is the set of
 * certificate digests seen when the app was first used (AnkiDroid) or picked (the others).
 */
class FlashcardAppChoice(private val store: KeyValueStore) {
    fun chosen(): FlashcardApp? = store.getString(KEY_CHOSEN)?.let { name -> FlashcardApp.entries.find { it.name == name } }

    fun pin(app: FlashcardApp): Set<String>? =
        store.getString(pinKey(app))?.split(DIGEST_SEPARATOR)?.filter { it.isNotEmpty() }?.toSet()

    fun save(app: FlashcardApp, digests: Set<String>, chosen: Choice) {
        val changes = mutableMapOf<String, Any?>(pinKey(app) to digests.sorted().joinToString(DIGEST_SEPARATOR))
        if (chosen == Choice.Chosen) {
            changes[KEY_CHOSEN] = app.name
        }
        store.edit(changes)
    }

    /** Drops [app] as the choice together with its pin. */
    fun forget(app: FlashcardApp) {
        store.edit(mapOf(KEY_CHOSEN to null, pinKey(app) to null))
    }

    /** Whether [save] also makes the app the user's choice, or only pins it (AnkiDroid's first use). */
    enum class Choice { Chosen, PinOnly }

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
 *   the authority while the real package is missing is never used.
 * - The user's choice stands while it is a candidate and its certificates still contain the pin;
 *   otherwise the choice and its pin are dropped and the default applies.
 * - AnkiDroid (the default) is used without asking, as before; its certificates are pinned on
 *   first use. If they later differ from the pin, the user is asked again.
 * - Any other app is used only after [choose].
 */
class FlashcardAppResolver(private val installed: InstalledApps, private val choice: FlashcardAppChoice) {
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

    /** Makes [app] the user's choice and pins its certificates now. False if it is not a candidate. */
    @Synchronized
    fun choose(app: FlashcardApp): Boolean {
        if (app !in candidates()) {
            return false
        }
        val digests = installed.signerDigests(app.packageName) ?: return false
        choice.save(app, digests, FlashcardAppChoice.Choice.Chosen)
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

        // Then the default, trusted on first use.
        val default = FlashcardApp.DEFAULT
        if (default in candidates && trustDefault()) {
            return FlashcardAppState.Ready(default, candidates)
        }

        if (candidates.isEmpty()) {
            return FlashcardAppState.NoneInstalled
        }
        return FlashcardAppState.NeedsChoice(candidates)
    }

    private fun candidates(): List<FlashcardApp> = FlashcardApp.entries.filter { app ->
        installed.signerDigests(app.packageName) != null && installed.authorityHolder(app.authority) == app.packageName
    }

    private fun pinMatches(app: FlashcardApp): Boolean {
        val pin = choice.pin(app) ?: return false
        val digests = installed.signerDigests(app.packageName) ?: return false
        return pin.isNotEmpty() && digests.containsAll(pin)
    }

    /** Pins the default's certificates the first time; afterwards they must contain the pin. */
    private fun trustDefault(): Boolean {
        val default = FlashcardApp.DEFAULT
        if (choice.pin(default) != null) {
            return pinMatches(default)
        }
        val digests = installed.signerDigests(default.packageName) ?: return false
        if (digests.isEmpty()) {
            return false
        }
        choice.save(default, digests, FlashcardAppChoice.Choice.PinOnly)
        return true
    }
}
