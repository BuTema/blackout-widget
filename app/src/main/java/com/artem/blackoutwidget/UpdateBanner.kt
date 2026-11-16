package com.artem.blackoutwidget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** A newer build of the app is published: [build] identifies it, [url] is where its APK is. */
class UpdateInfo(val build: String, val url: String)

/**
 * "A new version is available" card for the home screen. It exists only in the build that is installed by
 * hand from GitHub; the Google Play build updates through Google Play and shows nothing here.
 */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    if (!AppUpdater.enabled) return
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) { info = AppUpdater.check(ctx) }
    val update = info ?: return
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.update_available), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.update_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    AppUpdater.dismiss(ctx, update)
                    info = null
                }) { Text(stringResource(R.string.update_later)) }
                Button(onClick = { AppUpdater.install(ctx, update) }) { Text(stringResource(R.string.update_install)) }
            }
        }
    }
}
