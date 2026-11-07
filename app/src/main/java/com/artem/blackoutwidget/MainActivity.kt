package com.artem.blackoutwidget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scheduler.ensure(this)
        Notifier.ensureChannels(this)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // From Android 15 apps draw edge to edge, so keep content clear of the system bars.
                    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) { AppRoot() }
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val ctx = LocalContext.current
    // First launch (no address or queue yet) opens settings; afterwards the schedule is the home screen.
    var showSettings by rememberSaveable {
        val s = Prefs.load(ctx)
        mutableStateOf(s.street.isBlank() && s.manualGroup.isBlank() && s.detectedGroup.isBlank())
    }
    BackHandler(enabled = showSettings) { showSettings = false }
    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false })
    } else {
        ScheduleScreen(onOpenSettings = { showSettings = true })
    }
}
