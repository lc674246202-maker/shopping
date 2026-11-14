package com.lc.shoppingrecord.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.lc.shoppingrecord.R
import com.lc.shoppingrecord.model.DateOnly
import java.math.BigDecimal
import kotlin.math.max

class PriceHistoryChartView(context: Context) : View(context) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.green)
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.green)
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.line)
        strokeWidth = dp(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.muted)
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }
    private var points: List<Pair<String, Float>> = emptyList()

    fun setValues(values: List<Pair<String?, BigDecimal>>) {
        points = values.takeLast(8).map { (label, value) ->
            DateOnly.shortLabel(label) to value.toFloat()
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(188f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        val left = dp(18f)
        val right = width - dp(18f)
        val top = dp(18f)
        val bottom = height - dp(30f)
        repeat(4) { index ->
            val y = top + (bottom - top) * index / 3f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        val values = points.map { it.second }
        val min = values.minOrNull() ?: 0f
        val maxValue = values.maxOrNull() ?: min
        val range = max(maxValue - min, max(maxValue * 0.08f, 0.01f))
        fun x(index: Int): Float = if (points.size == 1) {
            (left + right) / 2f
        } else {
            left + (right - left) * index / (points.size - 1f)
        }
        fun y(value: Float): Float = bottom - ((value - min) / range) * (bottom - top)
        val path = Path()
        points.forEachIndexed { index, point ->
            val px = x(index)
            val py = y(point.second)
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, linePaint)
        points.forEachIndexed { index, point ->
            val px = x(index)
            val py = y(point.second)
            canvas.drawCircle(px, py, dp(4f), pointPaint)
            canvas.drawText(point.first, px, height - dp(9f), labelPaint)
        }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun sp(value: Float) = value * resources.displayMetrics.scaledDensity
}
