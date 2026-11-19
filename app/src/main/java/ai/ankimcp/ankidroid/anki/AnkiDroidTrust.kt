package ai.ankimcp.ankidroid.anki

/** What PackageManager says about a package; the Android side is `AndroidInstalledApps`. */
interface InstalledApps {
    /** SHA-256 hex of the package's signing certificates (with rotation history), or null if not installed. */
    fun signerDigests(packageName: String): Set<String>?

    /** The package that holds [authority], or null if none does. */
    fun authorityHolder(authority: String): String?
}

/** How this app was built: a debuggable build also accepts a self-built AnkiDroid (D66). */
enum class AppBuild { Release, Debuggable }

/** Whether AnkiDroid's provider may be used (D66). */
enum class ProviderTrust {
    Trusted,

    /** No `com.ichi2.anki` package. */
    NotInstalled,

    /** `com.ichi2.anki` is installed, but its authority belongs to another package or to none. */
    OtherHolder,

    /** `com.ichi2.anki` holds the authority but is not signed with AnkiDroid's release key. */
    UnknownSigner,
}

/**
 * Checks that AnkiDroid's authority is held by the `com.ichi2.anki` package and that the package
 * is signed with AnkiDroid's release key (D66). The key is built in, so a package that took over
 * the name while AnkiDroid was missing is never used, even on its first use.
 */
class AnkiDroidCheck(private val installed: InstalledApps, private val build: AppBuild) {
    fun trust(): ProviderTrust {
        val app = FlashcardApp.ANKIDROID
        val digests = installed.signerDigests(app.packageName) ?: return ProviderTrust.NotInstalled

        // The authority must lead to AnkiDroid itself, not to an app squatting it.
        if (installed.authorityHolder(app.authority) != app.packageName) {
            return ProviderTrust.OtherHolder
        }

        // The release key, current or earlier in the rotation history.
        if (digests.any { it.equals(RELEASE_CERT_SHA256, ignoreCase = true) }) {
            return ProviderTrust.Trusted
        }

        // A debuggable build of this app is for development against a self-built AnkiDroid.
        if (build == AppBuild.Debuggable) {
            return ProviderTrust.Trusted
        }
        return ProviderTrust.UnknownSigner
    }

    companion object {
        /**
         * SHA-256 of AnkiDroid's release signing certificate (CN=Nicolas Raoul, O=http://nrw.free.fr),
         * used for its Google Play and GitHub releases.
         */
        const val RELEASE_CERT_SHA256 = "2071534F0F4B5E54AE952DD275D70DA6E3459EE69909D2AB1B4843C4C5B21A45"
    }
}
