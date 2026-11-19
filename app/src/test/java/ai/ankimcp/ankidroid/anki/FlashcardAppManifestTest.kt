package ai.ankimcp.ankidroid.anki

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FlashcardAppManifestTest {
    /** Without these, Android hides the app (package visibility) or never shows its permission dialog (D65). */
    @Test
    fun `the manifest declares every known flashcard app`() {
        // Gradle runs unit tests from the module directory.
        val manifest = File("src/main/AndroidManifest.xml").readText()

        for (app in FlashcardApp.entries) {
            assertTrue(app.permission, manifest.contains("<uses-permission android:name=\"${app.permission}\""))
            assertTrue(app.packageName, manifest.contains("<package android:name=\"${app.packageName}\""))
            assertTrue(app.authority, manifest.contains("<provider android:authorities=\"${app.authority}\""))
        }
    }
}
