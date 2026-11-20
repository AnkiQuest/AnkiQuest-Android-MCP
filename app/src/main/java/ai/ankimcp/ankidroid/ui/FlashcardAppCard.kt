// SPDX-License-Identifier: AGPL-3.0-or-later

package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.anki.FlashcardApp
import ai.ankimcp.ankidroid.anki.FlashcardAppState
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/**
 * The "Flashcard app" picker (D65): the installed known apps, by their own name and icon. Picking
 * one pins its certificate ([onChoose]) and then asks for its database permission, so Android's
 * dialog names the app the user is granting access to.
 */
@Composable
fun FlashcardAppCard(
    title: String,
    state: FlashcardAppState,
    onChoose: (FlashcardApp) -> Unit,
    onEnvironmentChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onEnvironmentChanged()
    }
    val selected = (state as? FlashcardAppState.Ready)?.app

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                if (selected == null) {
                    "Pick the flashcard app whose collection this app may use."
                } else {
                    "The flashcard app whose collection this app uses."
                },
            )
            for (app in state.candidates) {
                FlashcardAppRow(
                    label = remember(app) { appLabel(context, app) },
                    icon = remember(app) { appIcon(context, app) },
                    selected = app == selected,
                    onClick = {
                        onChoose(app)
                        permissionLauncher.launch(app.permission)
                    },
                )
            }
        }
    }
}

@Composable
private fun FlashcardAppRow(label: String, icon: ImageBitmap?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(32.dp))
        }
        Text(label)
    }
}

/** The installed app's own label; our [FlashcardApp.displayName] if PackageManager has none. */
private fun appLabel(context: Context, app: FlashcardApp): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(app.packageName, 0)).toString()
} catch (_: PackageManager.NameNotFoundException) {
    app.displayName
}

private fun appIcon(context: Context, app: FlashcardApp): ImageBitmap? = try {
    context.packageManager.getApplicationIcon(app.packageName).toBitmap().asImageBitmap()
} catch (_: PackageManager.NameNotFoundException) {
    null
}
