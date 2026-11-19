package ai.ankimcp.ankidroid.anki

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.security.MessageDigest

/**
 * AnkiDroid's installed version. [code] is the package's `versionCode` with the ABI digit that
 * AnkiDroid's per-ABI builds add (`abi * 100000000`) removed, so 2.24.0 is 22400300 in every
 * build (AnkiDroid's scheme: major, 2-digit minor, 2-digit patch, build type 0-3 with 3 =
 * release, 2-digit build number).
 */
data class AnkiDroidVersion(val code: Long, val name: String) {
    fun atLeast(required: RequiredVersion): Boolean = code >= required.code

    companion object {
        fun of(rawVersionCode: Long, versionName: String?): AnkiDroidVersion =
            AnkiDroidVersion(rawVersionCode % ABI_FACTOR, versionName ?: rawVersionCode.toString())

        private const val ABI_FACTOR = 100_000_000L
    }
}

/** A minimum AnkiDroid release: [code] in [AnkiDroidVersion.code]'s scheme, [label] for messages. */
data class RequiredVersion(val code: Long, val label: String) {
    companion object {
        /** `cards` search and the scheduling/FSRS card columns (D57). */
        val TOOL_SET = RequiredVersion(22400300, "2.24")

        /** Card `flags` read and write. */
        val CARD_FLAGS = RequiredVersion(22500300, "2.25")
    }
}

/**
 * Whether the flashcard app in use (AnkiDroid unless the user picked another, D65) is ready, which
 * version, and whether we hold its database permission.
 */
interface AnkiDroidAvailability {
    fun isInstalled(): Boolean

    fun hasPermission(): Boolean

    /** Null if no flashcard app is ready. */
    fun version(): AnkiDroidVersion?
}

/** Resolves the app on each check, so a changed certificate or authority is caught before each use. */
class AndroidAnkiDroidAvailability(
    private val context: Context,
    private val apps: FlashcardAppResolver,
) : AnkiDroidAvailability {
    override fun isInstalled(): Boolean = ready() != null

    override fun hasPermission(): Boolean {
        val app = ready() ?: return false
        return ContextCompat.checkSelfPermission(context, app.permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun version(): AnkiDroidVersion? {
        val info = ready()?.let { packageInfo(context.packageManager, it.packageName, 0) } ?: return null
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return AnkiDroidVersion.of(code, info.versionName)
    }

    private fun ready(): FlashcardApp? = (apps.resolve() as? FlashcardAppState.Ready)?.app
}

/** [InstalledApps] over PackageManager: signing certificates and the authority's holder. */
class AndroidInstalledApps(context: Context) : InstalledApps {
    private val pm = context.packageManager

    override fun signerDigests(packageName: String): Set<String>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = packageInfo(pm, packageName, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo ?: return null
            // Several signers: all are current. One signer: its rotation history, newest last.
            val signers = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
            return signers.orEmpty().map { sha256(it.toByteArray()) }.toSet()
        }
        @Suppress("DEPRECATION")
        val info = packageInfo(pm, packageName, PackageManager.GET_SIGNATURES) ?: return null
        @Suppress("DEPRECATION")
        return info.signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    override fun authorityHolder(authority: String): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0))?.packageName
    } else {
        @Suppress("DEPRECATION")
        pm.resolveContentProvider(authority, 0)?.packageName
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private fun packageInfo(pm: PackageManager, packageName: String, flags: Int): PackageInfo? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(packageName, flags)
    }
} catch (_: PackageManager.NameNotFoundException) {
    null
}
