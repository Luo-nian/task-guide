package com.taskbar.app.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.taskbar.app.MainActivity
import com.taskbar.app.R
import com.taskbar.app.TaskBarApp
import com.taskbar.app.data.model.TrackCardItem
import kotlinx.coroutines.runBlocking

class TrackWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TrackWidgetFactory(applicationContext)
}

class TrackWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<TrackCardItem> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        // RemoteViewsFactory 允许同步阻塞取数
        val app = context.applicationContext as TaskBarApp
        items = runBlocking { app.repo.getTrackCards() }
    }

    override fun onDestroy() { items = emptyList() }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val item = items[position]
        val rv = RemoteViews(context.packageName, R.layout.widget_track_item)
        rv.setTextViewText(R.id.item_title, item.task.title)
        val stepText = item.currentStep?.let {
            val attr = if (it.attrValue.isNotEmpty()) "  · ${it.attrLabel} ${it.attrValue}" else ""
            "${it.title}$attr"
        } ?: "（无步骤，点击完成）"
        rv.setTextViewText(R.id.item_step, stepText)

        // 点击打开详情
        val fillIntent = Intent().apply {
            putExtra("task_uuid", item.task.uuid)
        }
        rv.setOnClickFillInIntent(R.id.item_root, fillIntent)
        return rv
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
