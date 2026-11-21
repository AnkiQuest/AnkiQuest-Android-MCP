// SPDX-License-Identifier: AGPL-3.0-or-later

package ai.ankimcp.ankidroid.anki

import ai.ankimcp.ankidroid.anki.FlashcardApp.ANKIDROID
import ai.ankimcp.ankidroid.anki.FlashcardApp.ANKIQUEST
import ai.ankimcp.ankidroid.anki.FlashcardApp.ANKIQUEST_RELEASED
import ai.ankimcp.ankidroid.settings.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashcardAppResolverTest {
    /** Packages with their signer digests, and which package holds each authority. */
    private class FakeInstalledApps : InstalledApps {
        val signers = mutableMapOf<String, Set<String>>()
        val authorities = mutableMapOf<String, String>()

        fun install(app: FlashcardApp, cert: String = CERT_OF.getValue(app)) {
            signers[app.packageName] = setOf(cert)
            authorities[app.authority] = app.packageName
        }

        fun uninstall(app: FlashcardApp) {
            signers.remove(app.packageName)
            authorities.remove(app.authority)
        }

        override fun signerDigests(packageName: String): Set<String>? = signers[packageName]

        override fun authorityHolder(authority: String): String? = authorities[authority]
    }

    private val installed = FakeInstalledApps()
    private val store = InMemoryKeyValueStore()

    private fun resolver(build: AppBuild = AppBuild.Release) = FlashcardAppResolver(installed, FlashcardAppChoice(store), build)

    @Test
    fun `none installed`() {
        assertEquals(FlashcardAppState.NoneInstalled, resolver().resolve())
        assertNull(resolver().current)
    }

    @Test
    fun `AnkiDroid only is used without asking and without a pin`() {
        installed.install(ANKIDROID)
        val resolver = resolver()

        assertEquals(FlashcardAppState.Ready(ANKIDROID, listOf(ANKIDROID)), resolver.resolve())
        assertEquals(ANKIDROID, resolver.current)
        assertEquals(ProviderTrust.Trusted, resolver.ankiDroidTrust())
        assertNull(FlashcardAppChoice(store).pin(ANKIDROID))
        assertNull(FlashcardAppChoice(store).chosen())
    }

    @Test
    fun `released AnkiQuest only needs an explicit choice`() {
        installed.install(ANKIQUEST_RELEASED)
        val resolver = resolver()

        assertEquals(FlashcardAppState.NeedsChoice(listOf(ANKIQUEST_RELEASED)), resolver.resolve())

        assertTrue(resolver.choose(ANKIQUEST_RELEASED))
        assertEquals(FlashcardAppState.Ready(ANKIQUEST_RELEASED, listOf(ANKIQUEST_RELEASED)), resolver.resolve())
        assertEquals(setOf(ANKIQUEST_RELEASED_CERT), FlashcardAppChoice(store).pin(ANKIQUEST_RELEASED))
    }

    @Test
    fun `released AnkiQuest with a changed certificate asks again`() {
        installed.install(ANKIQUEST_RELEASED)
        val resolver = resolver()
        resolver.choose(ANKIQUEST_RELEASED)

        installed.install(ANKIQUEST_RELEASED, cert = OTHER_CERT)

        assertEquals(FlashcardAppState.NeedsChoice(listOf(ANKIQUEST_RELEASED)), resolver.resolve())
        assertNull(FlashcardAppChoice(store).chosen())
    }

    @Test
    fun `both AnkiQuest packages are offered and the choice decides`() {
        installed.install(ANKIQUEST)
        installed.install(ANKIQUEST_RELEASED)
        val resolver = resolver()
        val both = listOf(ANKIQUEST, ANKIQUEST_RELEASED)

        assertEquals(FlashcardAppState.NeedsChoice(both), resolver.resolve())

        assertTrue(resolver.choose(ANKIQUEST_RELEASED))
        assertEquals(FlashcardAppState.Ready(ANKIQUEST_RELEASED, both), resolver.resolve())
    }

    @Test
    fun `AnkiQuest only needs an explicit choice`() {
        installed.install(ANKIQUEST)
        val resolver = resolver()

        assertEquals(FlashcardAppState.NeedsChoice(listOf(ANKIQUEST)), resolver.resolve())
        assertNull(resolver.current)

        assertTrue(resolver.choose(ANKIQUEST))
        assertEquals(FlashcardAppState.Ready(ANKIQUEST, listOf(ANKIQUEST)), resolver.resolve())
        assertEquals(ANKIQUEST, resolver.current)
    }

    @Test
    fun `both installed uses AnkiDroid until AnkiQuest is picked`() {
        installed.install(ANKIDROID)
        installed.install(ANKIQUEST)
        val resolver = resolver()
        val both = listOf(ANKIDROID, ANKIQUEST)

        assertEquals(FlashcardAppState.Ready(ANKIDROID, both), resolver.resolve())

        assertTrue(resolver.choose(ANKIQUEST))
        assertEquals(FlashcardAppState.Ready(ANKIQUEST, both), resolver.resolve())

        assertTrue(resolver.choose(ANKIDROID))
        assertEquals(FlashcardAppState.Ready(ANKIDROID, both), resolver.resolve())
    }

    @Test
    fun `an uninstalled choice falls back to AnkiDroid`() {
        installed.install(ANKIDROID)
        installed.install(ANKIQUEST)
        val resolver = resolver()
        resolver.choose(ANKIQUEST)

        installed.uninstall(ANKIQUEST)

        assertEquals(FlashcardAppState.Ready(ANKIDROID, listOf(ANKIDROID)), resolver.resolve())
        assertNull(FlashcardAppChoice(store).chosen())
        assertNull(FlashcardAppChoice(store).pin(ANKIQUEST))
    }

    @Test
    fun `an uninstalled choice without AnkiDroid leaves nothing installed`() {
        installed.install(ANKIQUEST)
        val resolver = resolver()
        resolver.choose(ANKIQUEST)

        installed.uninstall(ANKIQUEST)

        assertEquals(FlashcardAppState.NoneInstalled, resolver.resolve())
        assertNull(resolver.current)
    }

    @Test
    fun `a changed certificate resets the choice`() {
        installed.install(ANKIQUEST)
        val resolver = resolver()
        resolver.choose(ANKIQUEST)

        installed.install(ANKIQUEST, cert = OTHER_CERT)

        assertEquals(FlashcardAppState.NeedsChoice(listOf(ANKIQUEST)), resolver.resolve())
        assertNull(FlashcardAppChoice(store).chosen())
        assertNull(resolver.current)

        // Picking it again pins the new certificate.
        assertTrue(resolver.choose(ANKIQUEST))
        assertEquals(FlashcardAppState.Ready(ANKIQUEST, listOf(ANKIQUEST)), resolver.resolve())
    }

    @Test
    fun `AnkiDroid with an unknown certificate is never used, even on first use`() {
        installed.install(ANKIDROID, cert = OTHER_CERT)
        val resolver = resolver()

        assertEquals(FlashcardAppState.NoneInstalled, resolver.resolve())
        assertEquals(ProviderTrust.UnknownSigner, resolver.ankiDroidTrust())
        assertFalse(resolver.choose(ANKIDROID))
        assertNull(resolver.current)
    }

    @Test
    fun `a changed AnkiDroid certificate stops its use`() {
        installed.install(ANKIDROID)
        val resolver = resolver()
        resolver.resolve()

        installed.install(ANKIDROID, cert = OTHER_CERT)

        assertEquals(FlashcardAppState.NoneInstalled, resolver.resolve())
        assertNull(resolver.current)
    }

    @Test
    fun `AnkiDroid with an unknown certificate leaves AnkiQuest to pick`() {
        installed.install(ANKIDROID, cert = OTHER_CERT)
        installed.install(ANKIQUEST)

        assertEquals(FlashcardAppState.NeedsChoice(listOf(ANKIQUEST)), resolver().resolve())
    }

    @Test
    fun `a debuggable build uses a self-built AnkiDroid`() {
        installed.install(ANKIDROID, cert = OTHER_CERT)

        assertEquals(FlashcardAppState.Ready(ANKIDROID, listOf(ANKIDROID)), resolver(AppBuild.Debuggable).resolve())
    }

    @Test
    fun `a chosen AnkiDroid stays chosen across its own key rotation`() {
        installed.install(ANKIDROID)
        installed.install(ANKIQUEST)
        val resolver = resolver()
        assertTrue(resolver.choose(ANKIDROID))
        assertNull(FlashcardAppChoice(store).pin(ANKIDROID))

        installed.signers[ANKIDROID.packageName] = setOf(ANKIDROID_CERT, OTHER_CERT)

        assertEquals(FlashcardAppState.Ready(ANKIDROID, listOf(ANKIDROID, ANKIQUEST)), resolver.resolve())
        assertEquals(ANKIDROID, FlashcardAppChoice(store).chosen())
    }

    @Test
    fun `a rotated certificate keeps AnkiDroid while the release key is in the history`() {
        installed.install(ANKIDROID)
        val resolver = resolver()
        resolver.resolve()

        installed.signers[ANKIDROID.packageName] = setOf(ANKIDROID_CERT, OTHER_CERT)

        assertEquals(FlashcardAppState.Ready(ANKIDROID, listOf(ANKIDROID)), resolver.resolve())
    }

    @Test
    fun `a rotated AnkiQuest certificate keeps the pin while the old one is in the history`() {
        installed.install(ANKIQUEST)
        val resolver = resolver()
        resolver.choose(ANKIQUEST)

        installed.signers[ANKIQUEST.packageName] = setOf(ANKIQUEST_CERT, OTHER_CERT)

        assertEquals(FlashcardAppState.Ready(ANKIQUEST, listOf(ANKIQUEST)), resolver.resolve())
    }

    @Test
    fun `an authority held by another package is rejected`() {
        // AnkiDroid is missing and another app squats its authority.
        installed.authorities[ANKIDROID.authority] = SQUATTER
        installed.signers[SQUATTER] = setOf(OTHER_CERT)

        assertEquals(FlashcardAppState.NoneInstalled, resolver().resolve())
        assertFalse(resolver().choose(ANKIDROID))
        assertEquals(ProviderTrust.NotInstalled, resolver().ankiDroidTrust())
    }

    @Test
    fun `a genuine AnkiDroid whose authority another package holds is rejected`() {
        installed.install(ANKIDROID)
        installed.authorities[ANKIDROID.authority] = SQUATTER

        assertEquals(FlashcardAppState.NoneInstalled, resolver(AppBuild.Debuggable).resolve())
        assertEquals(ProviderTrust.OtherHolder, resolver().ankiDroidTrust())
    }

    @Test
    fun `an installed app whose authority another package holds is rejected`() {
        installed.install(ANKIDROID)
        installed.install(ANKIQUEST)
        installed.authorities[ANKIQUEST.authority] = SQUATTER
        val resolver = resolver()

        assertEquals(FlashcardAppState.Ready(ANKIDROID, listOf(ANKIDROID)), resolver.resolve())
        assertFalse(resolver.choose(ANKIQUEST))
    }

    @Test
    fun `a pinned choice whose authority is taken over falls back to AnkiDroid`() {
        installed.install(ANKIDROID)
        installed.install(ANKIQUEST)
        val resolver = resolver()
        resolver.choose(ANKIQUEST)

        installed.authorities[ANKIQUEST.authority] = SQUATTER

        assertEquals(FlashcardAppState.Ready(ANKIDROID, listOf(ANKIDROID)), resolver.resolve())
    }

    private companion object {
        const val ANKIDROID_CERT = AnkiDroidCheck.RELEASE_CERT_SHA256
        const val ANKIQUEST_CERT = "b2"
        const val ANKIQUEST_RELEASED_CERT = "b3"
        const val OTHER_CERT = "c3"
        const val SQUATTER = "com.example.squatter"
        val CERT_OF = mapOf(
            ANKIDROID to ANKIDROID_CERT,
            ANKIQUEST to ANKIQUEST_CERT,
            ANKIQUEST_RELEASED to ANKIQUEST_RELEASED_CERT,
        )
    }
}
