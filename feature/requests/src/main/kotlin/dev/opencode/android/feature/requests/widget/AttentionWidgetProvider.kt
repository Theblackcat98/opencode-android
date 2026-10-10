package dev.opencode.android.feature.requests.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import dagger.hilt.android.AndroidEntryPoint
import dev.opencode.android.feature.requests.R
import dev.opencode.android.feature.requests.notifications.NotificationIntents
import javax.inject.Inject

/**
 * The home-screen widget (matrix J8): sessions running and requests waiting for the user, and a tap that opens the app.
 *
 * **It draws what the updater last knew and never reads the stores itself.** The system calls [onUpdate] when a widget
 * is placed and at the period its info declares. The live pushes come from [AttentionWidgetUpdater], which is injected
 * here, so a receiver with an injected field is the whole wiring and no Hilt entry point is needed.
 *
 * **Not exported.** Only the system sends `APPWIDGET_UPDATE`. A tap opens the launcher activity through its own pending
 * intent, so no other app reaches this receiver.
 */
@AndroidEntryPoint
class AttentionWidgetProvider : AppWidgetProvider() {

    @Inject
    lateinit var updater: AttentionWidgetUpdater

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        manager.updateAppWidget(appWidgetIds, attentionWidgetViews(context, updater.counts.value))
    }
}

/** The line views, in the order [AttentionWidgetPolicy] lists its lines. */
private val lineViews = listOf(R.id.widget_line_one, R.id.widget_line_two)

/**
 * The widget's `RemoteViews` for [counts].
 *
 * **A function of its inputs only.** The provider and the updater both draw through it, and a test inflates the same
 * views without a launcher, so the text and visibility a user sees are the ones the test reads.
 */
fun attentionWidgetViews(context: Context, counts: WidgetCounts?): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.attention_widget)
    views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))

    val rendering = AttentionWidgetPolicy.render(counts)

    // A line the rendering does not carry is hidden, so the two states never show together.
    val lines = (rendering as? WidgetRendering.Counts)?.lines.orEmpty()
    lineViews.forEachIndexed { index, viewId ->
        val line = lines.getOrNull(index)
        views.setViewVisibility(viewId, if (line == null) View.GONE else View.VISIBLE)
        if (line != null) views.setTextViewText(viewId, context.getString(line.label.textRes(), line.count))
    }

    val status = when (rendering) {
        WidgetRendering.OpenApp -> R.string.widget_open_app
        WidgetRendering.NothingActive -> R.string.widget_nothing_active
        is WidgetRendering.Counts -> null
    }
    views.setViewVisibility(R.id.widget_status, if (status == null) View.GONE else View.VISIBLE)
    if (status != null) views.setTextViewText(R.id.widget_status, context.getString(status))
    return views
}

private fun WidgetLabel.textRes(): Int = when (this) {
    WidgetLabel.WAITING_FOR_YOU -> R.string.widget_waiting
    WidgetLabel.RUNNING -> R.string.widget_running
}

/** The launcher activity, which is the one thing a tap on the widget does. */
private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    0,
    NotificationIntents.launchIntent(context),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)
