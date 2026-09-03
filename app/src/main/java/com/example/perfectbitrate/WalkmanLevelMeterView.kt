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

class WalkmanLevelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    var isLightMode = false
        set(value) {
            field = value
            updatePaintsForTheme()
            invalidate()
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8.0f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val infinityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * density
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 7.5f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }

    private val scaleLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.0f * density
    }

    private val segActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val segPeakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E5A93C")
        style = Paint.Style.FILL
    }

    private val segClipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4444")
        style = Paint.Style.FILL
    }

    private val segInactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    private var peakHoldDbL = -60f
    private var peakHoldDbR = -60f
    private var peakHoldTimeL = 0L
    private var peakHoldTimeR = 0L
    private val PEAK_HOLD_MS = 750L
    private val PEAK_DECAY_RATE = 60f

    private var lastDrawTime = 0L
    private val decayRateDbPerSec = 140f

    private var numSegments = 0
    private var segRectsL = arrayOf<RectF>()
    private var segRectsR = arrayOf<RectF>()
    private var scaleTickX = FloatArray(dbScale.size)
    private var meterLeft = 0f
    private var meterRight = 0f
    private var scaleY = 0f
    private var scaleLineY = 0f
    private var barL_Top = 0f
    private var barHeight = 0f
    private var barR_Top = 0f
    private var lCenterY = 0f
    private var rCenterY = 0f

    init {
        updatePaintsForTheme()
    }

    private fun updatePaintsForTheme() {
        if (isLightMode) {
            // ★ ライトモード: 白背景で鮮明に見える黒/チャコールのアクティブバー
            segActivePaint.color = Color.parseColor("#1C1C1E")
            segInactivePaint.color = Color.parseColor("#E5E5EA")
            segPeakPaint.color = Color.parseColor("#D49B28")
            labelPaint.color = Color.parseColor("#1C1C1E")
            textPaint.color = Color.parseColor("#636366")
            infinityPaint.color = Color.parseColor("#636366")
            scaleLinePaint.color = Color.parseColor("#C7C7CC")
        } else {
            // ★ ダークモード: 1ミリも変えず原型のまま100%保持
            segActivePaint.color = Color.parseColor("#FFFFFF")
            segInactivePaint.color = Color.parseColor("#161616")
            segPeakPaint.color = Color.parseColor("#E5A93C")
            labelPaint.color = Color.parseColor("#CCCCCC")
            textPaint.color = Color.parseColor("#888888")
            infinityPaint.color = Color.parseColor("#888888")
            scaleLinePaint.color = Color.parseColor("#2E2E2E")
        }
    }

    fun setLevels(dbL: Float, dbR: Float) {
        val safeL = if (dbL.isNaN() || dbL.isInfinite()) -60f else dbL.coerceIn(-60f, 6f)
        val safeR = if (dbR.isNaN() || dbR.isInfinite()) -60f else dbR.coerceIn(-60f, 6f)

        targetDbL = safeL
        targetDbR = safeR

        val now = System.currentTimeMillis()

        if (targetDbL > currentDbL) currentDbL = targetDbL
        if (targetDbR > currentDbR) currentDbR = targetDbR

        if (targetDbL >= peakHoldDbL) {
            peakHoldDbL = targetDbL
            peakHoldTimeL = now
        }
        if (targetDbR >= peakHoldDbR) {
            peakHoldDbR = targetDbR
            peakHoldTimeR = now
        }

        postInvalidateOnAnimation()
    }

    fun reset() {
        targetDbL = -60f
        targetDbR = -60f
        currentDbL = -60f
        currentDbR = -60f
        peakHoldDbL = -60f
        peakHoldDbR = -60f
        peakHoldTimeL = 0L
        peakHoldTimeR = 0L
        lastDrawTime = 0L
        postInvalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val wf = w.toFloat()
        val labelWidth = 11f * density
        meterLeft = labelWidth + 3f * density
        meterRight = wf - 3f * density
        val meterWidth = meterRight - meterLeft

        scaleY = 8.0f * density
        scaleLineY = scaleY + 3.0f * density

        barL_Top = scaleLineY + 2.5f * density
        barHeight = 2.8f * density
        val barGap = 3.0f * density
        barR_Top = barL_Top + barHeight + barGap

        val fontMetrics = labelPaint.fontMetrics
        val textCenterOffset = (fontMetrics.descent + fontMetrics.ascent) / 2f
        lCenterY = barL_Top + (barHeight / 2f) - textCenterOffset
        rCenterY = barR_Top + (barHeight / 2f) - textCenterOffset

        for (i in dbScale.indices) {
            val frac = dbToFraction(dbScale[i].second)
            scaleTickX[i] = meterLeft + meterWidth * frac
        }

        val segWidth = 2.2f * density
        val segGap = 1.0f * density
        val totalSegStep = segWidth + segGap
        numSegments = (meterWidth / totalSegStep).toInt()

        val rectsL = ArrayList<RectF>(numSegments)
        val rectsR = ArrayList<RectF>(numSegments)

        for (i in 0 until numSegments) {
            val segX = meterLeft + i * totalSegStep
            rectsL.add(RectF(segX, barL_Top, segX + segWidth, barL_Top + barHeight))
            rectsR.add(RectF(segX, barR_Top, segX + segWidth, barR_Top + barHeight))
        }
        segRectsL = rectsL.toTypedArray()
        segRectsR = rectsR.toTypedArray()
    }

    private fun dbToFraction(db: Float): Float {
        if (db <= -50f) return 0f
        if (db >= 0f) return 1.0f
        return when {
            db < -40f -> 0.0f + (db + 50f) / 10f * 0.15f
            db < -30f -> 0.15f + (db + 40f) / 10f * 0.17f
            db < -20f -> 0.32f + (db + 30f) / 10f * 0.20f
            db < -10f -> 0.52f + (db + 20f) / 10f * 0.22f
            db < -5f  -> 0.74f + (db + 10f) / 5f  * 0.14f
            else      -> 0.88f + (db + 5f)  / 5f  * 0.12f
        }.coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (numSegments <= 0 || segRectsL.size != numSegments || segRectsR.size != numSegments) return

        val now = System.currentTimeMillis()
        if (lastDrawTime > 0L) {
            val dt = (now - lastDrawTime) / 1000f
            if (currentDbL > targetDbL) {
                currentDbL = max(targetDbL, currentDbL - decayRateDbPerSec * dt)
            }
            if (currentDbR > targetDbR) {
                currentDbR = max(targetDbR, currentDbR - decayRateDbPerSec * dt)
            }

            if (now - peakHoldTimeL > PEAK_HOLD_MS && peakHoldDbL > -60f) {
                peakHoldDbL = max(-60f, peakHoldDbL - PEAK_DECAY_RATE * dt)
            }
            if (now - peakHoldTimeR > PEAK_HOLD_MS && peakHoldDbR > -60f) {
                peakHoldDbR = max(-60f, peakHoldDbR - PEAK_DECAY_RATE * dt)
            }
        }
        lastDrawTime = now

        for (i in dbScale.indices) {
            val x = scaleTickX[i]
            val text = dbScale[i].first
            if (text == "-∞") {
                canvas.drawText(text, x + 1.5f * density, scaleY + 0.5f * density, infinityPaint)
            } else {
                canvas.drawText(text, x, scaleY, textPaint)
            }
            canvas.drawLine(x, scaleLineY - 1.5f * density, x, scaleLineY + 1.5f * density, scaleLinePaint)
        }
        canvas.drawLine(meterLeft, scaleLineY, meterRight, scaleLineY, scaleLinePaint)

        canvas.drawText("L", 0f, lCenterY, labelPaint)
        canvas.drawText("R", 0f, rCenterY, labelPaint)

        val activeCountL = if (currentDbL <= -49.5f) 0 else (dbToFraction(currentDbL) * numSegments).toInt().coerceIn(0, numSegments)
        val activeCountR = if (currentDbR <= -49.5f) 0 else (dbToFraction(currentDbR) * numSegments).toInt().coerceIn(0, numSegments)

        val peakIdxL = if (peakHoldDbL <= -48f) -1 else (dbToFraction(peakHoldDbL) * numSegments).toInt().coerceIn(0, numSegments - 1)
        val peakIdxR = if (peakHoldDbR <= -48f) -1 else (dbToFraction(peakHoldDbR) * numSegments).toInt().coerceIn(0, numSegments - 1)

        val clipIdx = (0.96f * numSegments).toInt()

        for (i in 0 until numSegments) {
            val paint = when {
                i == peakIdxL && peakHoldDbL > -48f -> segPeakPaint
                i >= clipIdx && i < activeCountL -> segClipPaint
                i < activeCountL -> segActivePaint
                else -> segInactivePaint
            }
            canvas.drawRoundRect(segRectsL[i], 0.5f * density, 0.5f * density, paint)
        }

        for (i in 0 until numSegments) {
            val paint = when {
                i == peakIdxR && peakHoldDbR > -48f -> segPeakPaint
                i >= clipIdx && i < activeCountR -> segClipPaint
                i < activeCountR -> segActivePaint
                else -> segInactivePaint
            }
            canvas.drawRoundRect(segRectsR[i], 0.5f * density, 0.5f * density, paint)
        }

        val isStillDecaying = (currentDbL > targetDbL + 0.1f) ||
                              (currentDbR > targetDbR + 0.1f) ||
                              (peakHoldDbL > targetDbL + 0.1f) ||
                              (peakHoldDbR > targetDbR + 0.1f)

        if (isStillDecaying) {
            postInvalidateOnAnimation()
        } else {
            currentDbL = targetDbL
            currentDbR = targetDbR
            peakHoldDbL = targetDbL
            peakHoldDbR = targetDbR
        }
    }
}