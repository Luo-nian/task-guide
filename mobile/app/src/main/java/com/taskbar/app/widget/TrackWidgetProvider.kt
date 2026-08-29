package com.taskbar.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.taskbar.app.MainActivity
import com.taskbar.app.R
import com.taskbar.app.TaskBarApp
import com.taskbar.app.notify.ioScope
import kotlinx.coroutines.launch

class TrackWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> updateAll(context)
            ACTION_ADD_TASK -> {
                val open = Intent(context, MainActivity::class.java).apply {
                    putExtra("action", "add_task")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                PendingIntent.getActivity(
                    context, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ).send()
            }
        }
    }

    private fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, TrackWidgetProvider::class.java))
        ids.forEach { updateWidget(context, mgr, it) }
    }

    private fun updateWidget(context: Context, mgr: AppWidgetManager, widgetId: Int) {
        val rv = RemoteViews(context.packageName, R.layout.widget_track)

        // 标题
        rv.setTextViewText(R.id.widget_title, "任务指南 · 追踪中")

        // 列表（RemoteViewsService）
        val listIntent = Intent(context, TrackWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        rv.setRemoteAdapter(R.id.widget_list, listIntent)
        rv.setEmptyView(R.id.widget_list, R.id.widget_empty)

        // 刷新按钮
        val refreshIntent = Intent(context, TrackWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        rv.setOnClickPendingIntent(
            R.id.widget_refresh,
            PendingIntent.getBroadcast(
                context, widgetId, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

        // 添加按钮
        val addIntent = Intent(context, TrackWidgetProvider::class.java).apply { action = ACTION_ADD_TASK }
        rv.setOnClickPendingIntent(
            R.id.widget_add,
            PendingIntent.getBroadcast(
                context, 0, addIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

        // 列表项点击模板（打开 App 详情）
        val itemIntent = Intent(context, MainActivity::class.java)
        val itemPI = PendingIntent.getActivity(
            context, 0, itemIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        rv.setPendingIntentTemplate(R.id.widget_list, itemPI)

        mgr.updateAppWidget(widgetId, rv)
        mgr.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list)
    }

    companion object {
        const val ACTION_REFRESH = "com.taskbar.app.WIDGET_REFRESH"
        const val ACTION_ADD_TASK = "com.taskbar.app.WIDGET_ADD_TASK"

        /** 数据变更后调用，刷新所有小部件 */
        fun refreshAll(context: Context) {
            val intent = Intent(context, TrackWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                component = ComponentName(context, TrackWidgetProvider::class.java)
            }
            context.sendBroadcast(intent)
        }
    }
}
