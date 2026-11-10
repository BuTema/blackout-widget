package com.artem.blackoutwidget

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    /** The place a widget or a notification asked to open; consumed by the UI. */
    private val requestedPlace = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scheduler.ensure(this)
        Notifier.ensureChannels(this)
        requestedPlace.value = intent?.getStringExtra(EXTRA_PLACE)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // From Android 15 apps draw edge to edge, so keep content clear of the system bars.
                    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                        AppRoot(
                            requested = requestedPlace.value,
                            onRequestHandled = { requestedPlace.value = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedPlace.value = intent.getStringExtra(EXTRA_PLACE)
    }

    companion object {
        const val EXTRA_PLACE = "place"

        /**
         * An intent that opens the app on a given place. The data part makes intents for different places
         * distinct, so their PendingIntents do not overwrite each other.
         */
        fun openIntent(ctx: Context, placeId: String?): Intent {
            val intent = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (placeId != null) {
                intent.putExtra(EXTRA_PLACE, placeId)
                intent.data = Uri.parse("blackout://place/$placeId")
            }
            return intent
        }
    }
}

private const val SCREEN_SCHEDULE = "schedule"
private const val SCREEN_SETTINGS = "settings"
private const val SCREEN_EDIT = "edit"

@Composable
private fun AppRoot(requested: String?, onRequestHandled: () -> Unit) {
    val ctx = LocalContext.current
    // First launch (no place yet) opens the editor; afterwards the schedule is the home screen.
    var screen by rememberSaveable {
        mutableStateOf(if (Prefs.places(ctx).isEmpty()) SCREEN_EDIT else SCREEN_SCHEDULE)
    }
    var editId by rememberSaveable {
        mutableStateOf(if (Prefs.places(ctx).isEmpty()) PlaceJson.newId(emptyList()) else "")
    }
    var selectedId by rememberSaveable { mutableStateOf(Prefs.places(ctx).firstOrNull()?.id ?: "") }

    LaunchedEffect(requested) {
        if (requested != null) {
            if (Prefs.place(ctx, requested) != null) {
                selectedId = requested
                screen = SCREEN_SCHEDULE
            }
            onRequestHandled()
        }
    }

    BackHandler(enabled = screen != SCREEN_SCHEDULE) {
        screen = if (screen == SCREEN_EDIT) SCREEN_SETTINGS else SCREEN_SCHEDULE
    }

    when (screen) {
        SCREEN_EDIT -> PlaceEditorScreen(
            placeId = editId,
            onBack = { screen = SCREEN_SETTINGS },
            onSaved = {
                selectedId = it
            },
            onDeleted = {
                if (selectedId == it) selectedId = Prefs.places(ctx).firstOrNull()?.id ?: ""
                screen = SCREEN_SETTINGS
            },
        )
        SCREEN_SETTINGS -> SettingsScreen(
            onBack = { screen = SCREEN_SCHEDULE },
            onEditPlace = {
                editId = it
                screen = SCREEN_EDIT
            },
            onAddPlace = {
                editId = PlaceJson.newId(Prefs.places(ctx).map { p -> p.id })
                screen = SCREEN_EDIT
            },
        )
        else -> ScheduleScreen(
            placeId = selectedId,
            onSelectPlace = { selectedId = it },
            onOpenSettings = { screen = SCREEN_SETTINGS },
        )
    }
}
