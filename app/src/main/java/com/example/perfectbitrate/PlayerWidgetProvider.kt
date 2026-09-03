package com.example.perfectbitrate

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.widget.RemoteViews

class PlayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, lastTitle, lastArtist, lastArtwork, lastIsPlaying)
        }
    }

    companion object {
        private var lastTitle: String = "Perfect Bit Rate"
        private var lastArtist: String = "YouTube Music"
        private var lastArtwork: Bitmap? = null
        private var lastIsPlaying: Boolean = false

        fun updateAllWidgets(
            context: Context,
            title: String = lastTitle,
            artist: String = lastArtist,
            artwork: Bitmap? = lastArtwork,
            isPlaying: Boolean = lastIsPlaying
        ) {
            lastTitle = title
            lastArtist = artist
            lastArtwork = artwork
            lastIsPlaying = isPlaying

            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val thisWidget = ComponentName(context, PlayerWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget) ?: return

            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId, title, artist, artwork, isPlaying)
            }
        }

        private fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            title: String,
            artist: String,
            artwork: Bitmap?,
            isPlaying: Boolean
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_player)

            views.setTextViewText(R.id.widgetTextTitle, title)
            views.setTextViewText(R.id.widgetTextArtist, artist)
            views.setTextViewText(R.id.widgetBtnPlayPause, if (isPlaying) "❚❚" else "▶")

            if (artwork != null) {
                val density = context.resources.displayMetrics.density
                views.setImageViewBitmap(R.id.widgetImageArtwork, getRoundedCornerBitmap(artwork, 8f * density))
            } else {
                views.setImageViewResource(R.id.widgetImageArtwork, R.drawable.bg_badge_normal)
            }

            // 全体タップで MainActivity 起動
            val mainIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val mainPendingIntent = PendingIntent.getActivity(
                context, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, mainPendingIntent)

            // 操作ボタンの PendingIntent
            views.setOnClickPendingIntent(R.id.widgetBtnPrev, createServicePendingIntent(context, "ACTION_PREV", 101))
            views.setOnClickPendingIntent(
                R.id.widgetBtnPlayPause,
                createServicePendingIntent(context, if (isPlaying) "ACTION_PAUSE" else "ACTION_PLAY", 102)
            )
            views.setOnClickPendingIntent(R.id.widgetBtnNext, createServicePendingIntent(context, "ACTION_NEXT", 103))

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun createServicePendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, BitPerfectPlaybackService::class.java).apply {
                this.action = action
            }
            return PendingIntent.getService(
                context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun getRoundedCornerBitmap(bitmap: Bitmap, cornerRadius: Float): Bitmap {
            val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            return output
        }
    }
}