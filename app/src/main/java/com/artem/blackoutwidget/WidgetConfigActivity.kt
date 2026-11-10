package com.artem.blackoutwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Opens when a widget is added (and when it is reconfigured): asks which place the widget shows.
 * With a single place, or when the place was chosen in the app just before, it does not ask.
 */
class WidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // If the person backs out, the widget must not be added.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val places = Prefs.places(this)
        val pending = Prefs.takePendingWidgetPlace(this)
        val preset = places.firstOrNull { it.id == pending } ?: places.singleOrNull()
        if (preset != null || places.isEmpty()) {
            choose(preset?.id)
            return
        }

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.widget_pick_place), style = MaterialTheme.typography.headlineSmall)
                        for (place in places) {
                            Button(modifier = Modifier.fillMaxWidth(), onClick = { choose(place.id) }) {
                                Text(place.name.ifBlank { stringResource(R.string.default_home_name) })
                            }
                        }
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { finish() }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }
    }

    /** Remembers which place this widget shows and lets the system add it. */
    private fun choose(placeId: String?) {
        if (placeId != null) Prefs.assignWidget(this, appWidgetId, placeId)
        Scheduler.ensure(this)
        Scheduler.refreshNow(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        // Repaint right away: this also runs when a widget without a place asks to be given one.
        CoroutineScope(Dispatchers.Main).launch {
            ScheduleWidget().updateAll(applicationContext)
            finish()
        }
    }
}
