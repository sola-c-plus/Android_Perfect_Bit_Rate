package com.example.perfectbitrate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class WalkmanLevelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888")
        textSize = 7.5f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA")
        textSize = 8.5f * density
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }

    private val scaleLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A")
        strokeWidth = 1.0f * density
    }

    private val segActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E5A93C") // Walkman Signature Gold
        style = Paint.Style.FILL
    }

    private val segClipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4444") // 0dB Over Clip Red
        style = Paint.Style.FILL
    }

    private val segInactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1C1C") // 消灯グリッド
        style = Paint.Style.FILL
    }

    private val dbScale = listOf(
        Pair("-∞", -50f),
        Pair("-40", -40f),
        Pair("-30", -30f),
        Pair("-20", -20f),
        Pair("-10", -10f),
        Pair("-5", -5f),
        Pair("0", 0f)
    )

    private var targetDbL = -60f
    private var targetDbR = -60f
    private var currentDbL = -60f
    private var currentDbR = -60f

    private var lastDrawTime = 0L
    private val decayRateDbPerSec = 75f

    private val rectF = RectF()

    fun setLevels(dbL: Float, dbR: Float) {
        val safeL = if (dbL.isNaN() || dbL.isInfinite()) -60f else dbL.coerceIn(-60f, 6f)
        val safeR = if (dbR.isNaN() || dbR.isInfinite()) -60f else dbR.coerceIn(-60f, 6f)

        targetDbL = safeL
        targetDbR = safeR

        if (targetDbL > currentDbL) currentDbL = targetDbL
        if (targetDbR > currentDbR) currentDbR = targetDbR

        postInvalidateOnAnimation()
    }

    fun reset() {
        targetDbL = -60f
        targetDbR = -60f
        currentDbL = -60f
        currentDbR = -60f
        postInvalidateOnAnimation()
    }

    private fun dbToFraction(db: Float): Float {
        if (db <= -50f) return 0f
        if (db >= 0f) return 1.0f + min(0.08f, db / 20f)
        return when {
            db < -40f -> 0.0f + (db + 50f) / 10f * 0.14f
            db < -30f -> 0.14f + (db + 40f) / 10f * 0.18f
            db < -20f -> 0.32f + (db + 30f) / 10f * 0.22f
            db < -10f -> 0.54f + (db + 20f) / 10f * 0.26f
            db < -5f  -> 0.80f + (db + 10f) / 5f  * 0.10f
            else      -> 0.90f + (db + 5f)  / 5f  * 0.10f
        }.coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = System.currentTimeMillis()
        if (lastDrawTime > 0L) {
            val dt = (now - lastDrawTime) / 1000f
            if (currentDbL > targetDbL) {
                currentDbL = max(targetDbL, currentDbL - decayRateDbPerSec * dt)
            }
            if (currentDbR > targetDbR) {
                currentDbR = max(targetDbR, currentDbR - decayRateDbPerSec * dt)
            }
        }
        lastDrawTime = now

        val w = width.toFloat()
        val labelWidth = 14f * density
        val meterLeft = labelWidth + 4f * density
        val meterRight = w - 4f * density
        val meterWidth = meterRight - meterLeft

        val scaleY = 7.5f * density
        val scaleLineY = scaleY + 3f * density

        val barL_Top = scaleLineY + 2.5f * density
        val barHeight = 2.8f * density
        val barR_Top = barL_Top + barHeight + 2.5f * density

        for (item in dbScale) {
            val frac = dbToFraction(item.second)
            val x = meterLeft + meterWidth * frac
            canvas.drawText(item.first, x, scaleY, textPaint)
            canvas.drawLine(x, scaleLineY - 1.5f * density, x, scaleLineY + 1.5f * density, scaleLinePaint)
        }
        canvas.drawLine(meterLeft, scaleLineY, meterRight, scaleLineY, scaleLinePaint)

        canvas.drawText("L", 0f, barL_Top + barHeight - 0.5f * density, labelPaint)
        canvas.drawText("R", 0f, barR_Top + barHeight - 0.5f * density, labelPaint)

        val segWidth = 2.2f * density
        val segGap = 1.2f * density
        val totalSegStep = segWidth + segGap
        val numSegments = (meterWidth / totalSegStep).toInt()

        val fracL = dbToFraction(currentDbL)
        val fracR = dbToFraction(currentDbR)

        for (i in 0 until numSegments) {
            val segX = meterLeft + i * totalSegStep
            val segFrac = i.toFloat() / numSegments.toFloat()

            rectF.set(segX, barL_Top, segX + segWidth, barL_Top + barHeight)
            val paintL = when {
                segFrac <= fracL && segFrac >= 0.96f -> segClipPaint
                segFrac <= fracL -> segActivePaint
                else -> segInactivePaint
            }
            canvas.drawRoundRect(rectF, 0.5f * density, 0.5f * density, paintL)

            rectF.set(segX, barR_Top, segX + segWidth, barR_Top + barHeight)
            val paintR = when {
                segFrac <= fracR && segFrac >= 0.96f -> segClipPaint
                segFrac <= fracR -> segActivePaint
                else -> segInactivePaint
            }
            canvas.drawRoundRect(rectF, 0.5f * density, 0.5f * density, paintR)
        }

        if (currentDbL > -55f || currentDbR > -55f || targetDbL > -55f || targetDbR > -55f) {
            postInvalidateOnAnimation()
        }
    }
}