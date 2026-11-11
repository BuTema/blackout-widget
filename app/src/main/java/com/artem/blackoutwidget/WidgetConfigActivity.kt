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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
            AppTheme {
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
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { choose(place.id) },
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        place.name.ifBlank { stringResource(R.string.default_home_name) },
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    Text(
                                        placeSubtitle(place),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
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

    /**
     * Remembers which place this widget shows, paints it from the saved schedule at once, and closes.
     * Nothing is downloaded first: the schedule the app already has is shown immediately, and a fresh
     * download is started in the background only when there is none or it is old.
     */
    private fun choose(placeId: String?) {
        if (placeId != null) Prefs.assignWidget(this, appWidgetId, placeId)
        Scheduler.ensure(this)
        val cache = placeId?.let { Prefs.loadCache(this, it) }
        val fresh = cache != null && System.currentTimeMillis() - cache.updatedAtMillis < FRESH_MS
        if (!fresh) Scheduler.refreshNow(this)

        val app = applicationContext
        val id = appWidgetId
        CoroutineScope(Dispatchers.Main).launch {
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            // Close quickly even if the first paint is slow; the repeats below catch up.
            withTimeoutOrNull(2_000L) { paint(app, id) }
            finish()
            // The launcher may attach the widget a moment after this screen closes, and a repaint made
            // before that can be lost (the widget then keeps its "loading" picture): repeat it.
            for (wait in longArrayOf(1_200L, 3_000L, 8_000L)) {
                delay(wait)
                paint(app, id)
            }
        }
    }

    private suspend fun paint(app: android.content.Context, id: Int) {
        try {
            val glanceId = GlanceAppWidgetManager(app).getGlanceIdBy(id)
            ScheduleWidget().update(app, glanceId)
        } catch (e: Exception) {
            ScheduleWidget().updateAll(app)
        }
    }

    private companion object {
        const val FRESH_MS = 30L * 60L * 1000L
    }
}
