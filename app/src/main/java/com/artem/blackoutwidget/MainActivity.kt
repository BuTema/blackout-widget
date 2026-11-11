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
import androidx.compose.runtime.remember
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
            AppTheme(dark) {
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

private const val SCREEN_HOME = "home"
private const val SCREEN_DETAIL = "detail"
private const val SCREEN_SETTINGS = "settings"
private const val SCREEN_EDIT = "edit"

@Composable
private fun AppRoot(requested: String?, onRequestHandled: () -> Unit) {
    val ctx = LocalContext.current
    // First launch (no place yet) opens the editor; afterwards the list of places is the home screen.
    val firstRun = remember { Prefs.places(ctx).isEmpty() }
    var screen by rememberSaveable { mutableStateOf(if (firstRun) SCREEN_EDIT else SCREEN_HOME) }
    var editId by rememberSaveable { mutableStateOf(if (firstRun) PlaceJson.newId(emptyList()) else "") }
    // Where the editor returns to: the place's detail screen or the home screen.
    var editReturn by rememberSaveable { mutableStateOf(SCREEN_HOME) }
    var selectedId by rememberSaveable { mutableStateOf(Prefs.places(ctx).firstOrNull()?.id ?: "") }

    LaunchedEffect(requested) {
        if (requested != null) {
            if (Prefs.place(ctx, requested) != null) {
                selectedId = requested
                screen = SCREEN_DETAIL
            }
            onRequestHandled()
        }
    }

    BackHandler(enabled = screen != SCREEN_HOME) {
        screen = when (screen) {
            SCREEN_EDIT -> editReturn
            else -> SCREEN_HOME
        }
    }

    when (screen) {
        SCREEN_EDIT -> PlaceEditorScreen(
            placeId = editId,
            onBack = { screen = editReturn },
            onSaved = { selectedId = it },
            onDeleted = {
                if (selectedId == it) selectedId = Prefs.places(ctx).firstOrNull()?.id ?: ""
                screen = SCREEN_HOME
            },
        )
        SCREEN_SETTINGS -> SettingsScreen(onBack = { screen = SCREEN_HOME })
        SCREEN_DETAIL -> ScheduleScreen(
            placeId = selectedId,
            onBack = { screen = SCREEN_HOME },
            onEdit = {
                editId = selectedId
                editReturn = SCREEN_DETAIL
                screen = SCREEN_EDIT
            },
        )
        else -> HomeScreen(
            onOpenPlace = {
                selectedId = it
                screen = SCREEN_DETAIL
            },
            onAddPlace = {
                editId = PlaceJson.newId(Prefs.places(ctx).map { p -> p.id })
                editReturn = SCREEN_HOME
                screen = SCREEN_EDIT
            },
            onOpenSettings = { screen = SCREEN_SETTINGS },
        )
    }
}
