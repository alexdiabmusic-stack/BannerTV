package com.alexdiab.stadiatv.mobile

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.alexdiab.stadiatv.StadiaTvApplication
import com.alexdiab.stadiatv.data.UserSettings
import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.net.AppJson
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable data class WidgetGame(val title: String, val subtitle: String, val link: String)
@Serializable data class WidgetSnapshot(val updated: Long = 0, val games: List<WidgetGame> = emptyList())

object PhoneWidgets {
    fun requestPin(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(context, PhoneScoreWidget::class.java), null, null)
    }
    fun installed(context: Context): Boolean = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, PhoneScoreWidget::class.java)).isNotEmpty()
    fun publish(context: Context, matches: List<Match>, settings: UserSettings, checkedAt: Long) {
        if (!installed(context)) return
        val now = System.currentTimeMillis()
        val games = matches.filter { settings.inScope(it) }.sortedWith(compareBy<Match> { if (settings.involvesFavorite(it)) 0 else 1 }.thenBy { when (it.state) { GameState.LIVE -> 0; GameState.PRE -> 1; GameState.FINAL -> 2 } }.thenBy { kotlin.math.abs(it.startMillis - now) }).take(3).map { match ->
            val spoiler = settings.spoilerFreeMode && match.state == GameState.FINAL
            val event = match.league.group in setOf(com.alexdiab.stadiatv.domain.SportGroup.Racing, com.alexdiab.stadiatv.domain.SportGroup.Golf)
            WidgetGame(if (event) match.name else if (spoiler || match.state == GameState.PRE) match.shortName else match.displaySides.joinToString(" · ") { "${it.abbreviation} ${it.score ?: "–"}" }, "${match.league.shortName} · ${if (spoiler) "Final score hidden" else match.statusDetail}", Uri.Builder().scheme("banner").authority("game").appendQueryParameter("id", match.id).appendQueryParameter("league", match.league.path).appendQueryParameter("date", match.startMillis.toString()).build().toString())
        }
        context.getSharedPreferences("score-widget", Context.MODE_PRIVATE).edit().putString("snapshot", AppJson.encodeToString(WidgetSnapshot(checkedAt, games))).apply()
        render(context)
    }
    fun render(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PhoneScoreWidget::class.java))
        if (ids.isEmpty()) return
        val snapshot = runCatching { AppJson.decodeFromString<WidgetSnapshot>(context.getSharedPreferences("score-widget", Context.MODE_PRIVATE).getString("snapshot", "{}")!!) }.getOrDefault(WidgetSnapshot())
        val view = RemoteViews(context.packageName, R.layout.phone_score_widget)
        view.setTextViewText(R.id.widget_checked, if (snapshot.updated > 0) "Updated ${clockTime(snapshot.updated)} · tap refresh" else "Tap refresh to load your sports")
        val titleIds = listOf(R.id.widget_game_1, R.id.widget_game_2, R.id.widget_game_3)
        val subtitleIds = listOf(R.id.widget_detail_1, R.id.widget_detail_2, R.id.widget_detail_3)
        titleIds.forEachIndexed { index, title ->
            val game = snapshot.games.getOrNull(index)
            view.setTextViewText(title, game?.title ?: if (index == 0) "Your teams. Your sports." else "")
            view.setTextViewText(subtitleIds[index], game?.subtitle.orEmpty() + if (game != null && System.currentTimeMillis() - snapshot.updated > 90000) " · saved snapshot" else "")
            view.setOnClickPendingIntent(title, open(context, game?.link ?: "banner://following", index))
            view.setOnClickPendingIntent(subtitleIds[index], open(context, game?.link ?: "banner://following", index))
        }
        view.setOnClickPendingIntent(R.id.widget_title, open(context, "banner://home", 10))
        val refresh = Intent(context, PhoneScoreWidget::class.java).setAction(PhoneScoreWidget.REFRESH)
        view.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, 11, refresh, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        ids.forEach { manager.updateAppWidget(it, view) }
    }
    private fun open(context: Context, link: String, request: Int): PendingIntent = PendingIntent.getActivity(context, request, Intent(context, MobileActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** No continuous background live polling: launcher refresh is bounded and honours Android's battery policy. */
class PhoneScoreWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { PhoneWidgets.render(context); refresh(context) }
    override fun onReceive(context: Context, intent: Intent) { super.onReceive(context, intent); if (intent.action == REFRESH) refresh(context) }
    private fun refresh(context: Context) {
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as StadiaTvApplication).container
                withTimeoutOrNull(8000) { container.matchFeed.load() }
                val feed = container.matchFeed.sections.value
                if (feed.all.isNotEmpty()) PhoneWidgets.publish(context, feed.all, container.preferences.current, feed.updatedAt) else PhoneWidgets.render(context)
            } catch (_: Exception) { PhoneWidgets.render(context) } finally { result.finish() }
        }
    }
    companion object { const val REFRESH = "com.alexdiab.stadiatv.mobile.WIDGET_REFRESH" }
}
