package com.zg.sensormonitor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.zg.sensormonitor.R
import com.zg.sensormonitor.data.HistoryPoint

class TrendView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var points = emptyList<HistoryPoint>()
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.attr.appOutline); strokeWidth = 1f }
    private val range = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.attr.appTintSoft); style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.attr.appPositive); strokeWidth = 3f; style = Paint.Style.STROKE }
    fun submit(value: List<HistoryPoint>) { points = value; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 36f; val top = 24f; val right = width - 18f; val bottom = height - 32f
        repeat(5) { i -> val y = top + (bottom - top) * i / 4; canvas.drawLine(left, y, right, y, grid) }
        if (points.size < 2) return
        val min = points.minOf { it.min }; val max = points.maxOf { it.max }; val span = (max - min).takeIf { it > 0.0001 } ?: 1.0
        fun x(i: Int) = left + (right - left) * i / (points.size - 1)
        fun y(v: Double) = bottom - ((v - min) / span * (bottom - top)).toFloat()
        val band = android.graphics.Path().apply {
            moveTo(x(0), y(points[0].max)); points.indices.drop(1).forEach { lineTo(x(it), y(points[it].max)) }
            points.indices.reversed().forEach { lineTo(x(it), y(points[it].min)) }; close()
        }
        canvas.drawPath(band, range)
        val path = android.graphics.Path().apply { moveTo(x(0), y(points[0].average)); points.indices.drop(1).forEach { lineTo(x(it), y(points[it].average)) } }
        canvas.drawPath(path, line)
    }
}
