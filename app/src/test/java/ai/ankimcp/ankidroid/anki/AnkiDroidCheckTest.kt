// SPDX-License-Identifier: AGPL-3.0-or-later

package ai.ankimcp.ankidroid.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiDroidCheckTest {
    /** Packages by name with their signer digests, and authorities by holder. */
    private class FakeInstalledApps(
        val digests: MutableMap<String, Set<String>> = mutableMapOf(),
        val holders: MutableMap<String, String> = mutableMapOf(),
    ) : InstalledApps {
        override fun signerDigests(packageName: String): Set<String>? = digests[packageName]

        override fun authorityHolder(authority: String): String? = holders[authority]
    }

    private fun ankiDroid(vararg digests: String) = FakeInstalledApps(
        digests = mutableMapOf(ANKIDROID to digests.toSet()),
        holders = mutableMapOf(AUTHORITY to ANKIDROID),
    )

    private fun trust(installed: InstalledApps, build: AppBuild = AppBuild.Release) = AnkiDroidCheck(installed, build).trust()

    @Test
    fun `genuine AnkiDroid is trusted`() {
        assertEquals(ProviderTrust.Trusted, trust(ankiDroid(RELEASE)))
    }

    @Test
    fun `the release digest matches in any letter case`() {
        assertEquals(ProviderTrust.Trusted, trust(ankiDroid(RELEASE.uppercase())))
    }

    @Test
    fun `AnkiDroid from F-Droid is trusted`() {
        assertEquals(ProviderTrust.Trusted, trust(ankiDroid(FDROID)))
    }

    @Test
    fun `not installed`() {
        assertEquals(ProviderTrust.NotInstalled, trust(FakeInstalledApps()))
    }

    @Test
    fun `an app squatting the authority while AnkiDroid is missing counts as not installed`() {
        val installed = FakeInstalledApps(
            digests = mutableMapOf(SQUATTER to setOf(OTHER_KEY)),
            holders = mutableMapOf(AUTHORITY to SQUATTER),
        )

        assertEquals(ProviderTrust.NotInstalled, trust(installed))
    }

    @Test
    fun `another package holding the authority is not trusted`() {
        val installed = ankiDroid(RELEASE).apply {
            digests[SQUATTER] = setOf(OTHER_KEY)
            holders[AUTHORITY] = SQUATTER
        }

        assertEquals(ProviderTrust.OtherHolder, trust(installed))
        assertEquals(ProviderTrust.OtherHolder, trust(installed, AppBuild.Debuggable))
    }

    @Test
    fun `AnkiDroid without its provider is not trusted`() {
        val installed = ankiDroid(RELEASE).apply { holders.clear() }

        assertEquals(ProviderTrust.OtherHolder, trust(installed))
    }

    @Test
    fun `AnkiDroid's package with an unknown certificate is not trusted`() {
        assertEquals(ProviderTrust.UnknownSigner, trust(ankiDroid(OTHER_KEY)))
    }

    @Test
    fun `AnkiDroid's package without certificates is not trusted`() {
        assertEquals(ProviderTrust.UnknownSigner, trust(ankiDroid()))
    }

    @Test
    fun `a debuggable build accepts a self-built AnkiDroid`() {
        assertEquals(ProviderTrust.Trusted, trust(ankiDroid(OTHER_KEY), AppBuild.Debuggable))
    }

    @Test
    fun `a rotated key still matches through the release key in its history`() {
        assertEquals(ProviderTrust.Trusted, trust(ankiDroid(RELEASE, ROTATED_KEY)))
    }

    private companion object {
        const val ANKIDROID = "com.ichi2.anki"
        const val AUTHORITY = "com.ichi2.anki.flashcards"
        const val SQUATTER = "com.example.squatter"
        const val RELEASE = "2071534f0f4b5e54ae952dd275d70da6e3459ee69909d2ab1b4843c4c5b21a45"
        const val FDROID = "24a3d4756c62dd69c86df69aa7fa327b2a6bf55f55c4a1749f99bd1da75906bb"
        const val OTHER_KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        const val ROTATED_KEY = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100"
    }
}
