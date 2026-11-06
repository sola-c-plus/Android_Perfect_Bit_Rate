package com.example.perfectbitrate

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class WalkmanEqView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    val bandLabels = arrayOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K", "40K")
    val eqFrequencies = doubleArrayOf(31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    val gains = FloatArray(10) { 0.0f }

    val spectrumFrequencies = floatArrayOf(
        31.25f, 39.37f, 49.61f, 62.50f, 78.75f, 99.21f, 125.00f, 157.49f, 198.43f, 250.00f,
        314.98f, 396.85f, 500.00f, 629.96f, 793.70f, 1000.00f, 1259.92f, 1587.40f, 2000.00f,
        2519.84f, 3174.80f, 4000.00f, 5039.68f, 6349.60f, 8000.00f, 10079.37f, 12699.21f, 16000.00f,
        20158.74f, 25398.42f, 32000.00f, 40000.00f
    )
    val NUM_SPEC_BANDS = spectrumFrequencies.size

    var isEditMode = false
        set(value) {
            field = value
            invalidate()
        }

    var isDirectBypass = false
        set(value) {
            field = value
            invalidate()
        }

    var isSpectrumEnabled = true
        set(value) {
            field = value
            if (!value) {
                targetSpectrum.fill(-55f)
                currentSpectrum.fill(-55f)
            }
            invalidate()
        }

    var isLightMode = false
        set(value) {
            field = value
            updatePaintsForTheme()
            invalidate()
        }

    var selectedBandIndex = 7
        set(value) {
            field = value.coerceIn(0, 9)
            invalidate()
            onBandSelectedListener?.invoke(field, gains[field])
        }

    private var activeDragBandIndex = -1

    var onGainChangedListener: ((Int, Float, FloatArray) -> Unit)? = null
    var onBandSelectedListener: ((Int, Float) -> Unit)? = null

    private val targetSpectrum = FloatArray(NUM_SPEC_BANDS) { -55f }
    private val currentSpectrum = FloatArray(NUM_SPEC_BANDS) { -55f }

    // ★ スペクトラムの時間方向の平滑化は、データの到着間隔ではなく
    //   ディスプレイのフレーム周期（Choreographer）で駆動する。
    //   Local 再生では PCM がフロー制御でバースト状に供給され、
    //   ネイティブ側のスペクトラム算出（処理時点）もバースト状に
    //   進むため、到着した目標値へそのまま追従させると「止まっては
    //   跳ぶ」かくかくした動きになる。目標値は保持だけにし、表示値
    //   （currentSpectrum）はフレーム毎に時定数つきで目標へ近づける
    //   ことで、到着間隔によらず一定周期の滑らかな動きにする。
    //   攻撃側は指数平滑（時定数 SPECTRUM_ATTACK_TAU_SEC）、
    //   減衰側は従来どおり 75 dB/s の落下。
    private val SPECTRUM_ATTACK_TAU_SEC = 0.06f
    private val SPECTRUM_RELEASE_DB_PER_SEC = 75f
    private var spectrumAnimRunning = false
    private var lastSpectrumFrameNanos = 0L
    private val spectrumFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!spectrumAnimRunning) return
            val dt = if (lastSpectrumFrameNanos > 0L) {
                ((frameTimeNanos - lastSpectrumFrameNanos) / 1_000_000_000f)
                    .coerceIn(0f, 0.05f)
            } else {
                0.016f
            }
            lastSpectrumFrameNanos = frameTimeNanos
            if (isSpectrumEnabled && advanceSpectrum(dt)) {
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // 表示値を目標値へ dt 分だけ近づける。まだ動いている
    // （＝再描画が必要）なら true を返す。
    private fun advanceSpectrum(dt: Float): Boolean {
        val attackFactor = 1f - kotlin.math.exp(-dt / SPECTRUM_ATTACK_TAU_SEC)
        var moving = false
        for (i in 0 until NUM_SPEC_BANDS) {
            val target = targetSpectrum[i]
            val current = currentSpectrum[i]
            val next = if (target > current) {
                current + (target - current) * attackFactor
            } else {
                max(target, current - SPECTRUM_RELEASE_DB_PER_SEC * dt)
            }
            currentSpectrum[i] = next
            if (abs(next - target) > 0.05f) moving = true
        }
        return moving
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!spectrumAnimRunning) {
            spectrumAnimRunning = true
            lastSpectrumFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(spectrumFrameCallback)
        }
    }

    override fun onDetachedFromWindow() {
        spectrumAnimRunning = false
        Choreographer.getInstance().removeFrameCallback(spectrumFrameCallback)
        super.onDetachedFromWindow()
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hiResZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#18E5A93C")
        style = Paint.Style.FILL
    }
    private val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.0f * density
        style = Paint.Style.STROKE
    }
    private val cursorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        strokeWidth = 1.2f * density
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.0f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bypassCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    }
    private val spectrumLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        strokeWidth = 1.6f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val spectrumFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8.5f * density
        textAlign = Paint.Align.CENTER
    }
    private val hiResLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        textSize = 8.5f * density
        textAlign = Paint.Align.CENTER
    }
    private val selectedLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.0f * density
        textAlign = Paint.Align.CENTER
    }

    private var gridLeft = 0f
    private var gridRight = 0f
    private var gridTop = 0f
    private var gridBottom = 0f
    private var gridHeight = 0f
    private var gridWidth = 0f

    private val labelX = FloatArray(11)
    private val eqBandX = FloatArray(10)
    private val specBandX = FloatArray(NUM_SPEC_BANDS)

    private val curvePath = Path()
    private val spectrumPath = Path()
    private val spectrumFillPath = Path()

    init {
        try {
            val condensedTf = ResourcesCompat.getFont(context, R.font.roboto_condensed_bold)
            if (condensedTf != null) {
                labelPaint.typeface = condensedTf
                hiResLabelPaint.typeface = condensedTf
                selectedLabelPaint.typeface = condensedTf
            }
        } catch (e: Exception) {}
        updatePaintsForTheme()
    }

    private fun updatePaintsForTheme() {
        if (isLightMode) {
            gridPaint.color = ContextCompat.getColor(context, R.color.component_inactive_light)
            gridPaint.strokeWidth = 0.75f * density
            centerLinePaint.color = ContextCompat.getColor(context, R.color.component_line_light)
            centerLinePaint.strokeWidth = 1.0f * density
            gridBorderPaint.color = ContextCompat.getColor(context, R.color.component_border_light)
            curvePaint.color = ContextCompat.getColor(context, R.color.text_primary_light)
            bypassCurvePaint.color = ContextCompat.getColor(context, R.color.label_gray_light)
            labelPaint.color = ContextCompat.getColor(context, R.color.label_gray_light)
            selectedLabelPaint.color = ContextCompat.getColor(context, R.color.text_primary_light)
            pointPaint.color = ContextCompat.getColor(context, R.color.text_primary_light)
        } else {
            gridPaint.color = ContextCompat.getColor(context, R.color.divider)
            gridPaint.strokeWidth = 0.75f * density
            centerLinePaint.color = ContextCompat.getColor(context, R.color.grid_line)
            centerLinePaint.strokeWidth = 1.0f * density
            gridBorderPaint.color = ContextCompat.getColor(context, R.color.grid_border)
            curvePaint.color = ContextCompat.getColor(context, R.color.white)
            bypassCurvePaint.color = Color.parseColor("#444444")
            labelPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
            selectedLabelPaint.color = ContextCompat.getColor(context, R.color.white)
            pointPaint.color = ContextCompat.getColor(context, R.color.white)
        }
    }

    fun setSpectrumLevels(levels: FloatArray) {
        if (!isSpectrumEnabled) return
        val count = min(levels.size, NUM_SPEC_BANDS)
        for (i in 0 until count) {
            val safeDb = if (levels[i].isNaN() || levels[i].isInfinite()) -55f else levels[i].coerceIn(-55f, 0f)
            targetSpectrum[i] = safeDb
        }
        // ★ 目標値を差し替えるだけ。再描画と時間方向の平滑化は
        //   フレーム周期のコールバック（spectrumFrameCallback）が担う。
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast((150 * density).toInt())
        val topPadding = 6f * density
        val labelAreaHeight = 18f * density
        val leftPadding = 12f * density
        val rightPadding = 12f * density
        val gridH = h.toFloat() - topPadding - labelAreaHeight
        val desiredGridW = gridH * 1.45f
        val totalW = (desiredGridW + leftPadding + rightPadding).toInt()

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val finalW = if (widthMode == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(widthMeasureSpec)
        } else {
            totalW
        }
        setMeasuredDimension(finalW, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val labelAreaHeight = 18f * density
        val topPadding = 6f * density
        val leftPadding = 12f * density
        val rightPadding = 12f * density
        val availableHeight = h.toFloat() - topPadding - labelAreaHeight

        gridHeight = availableHeight
        val desiredWidth = gridHeight * 1.45f
        val availableWidth = (w.toFloat() - leftPadding - rightPadding).coerceAtLeast(10f)
        gridWidth = min(availableWidth, desiredWidth)

        gridLeft = leftPadding
        gridRight = gridLeft + gridWidth
        gridTop = topPadding
        gridBottom = gridTop + gridHeight

        val stepX = gridWidth / 10f
        for (i in 0..10) {
            labelX[i] = gridLeft + i * stepX
            if (i < 10) {
                eqBandX[i] = labelX[i]
            }
        }

        for (i in 0 until NUM_SPEC_BANDS) {
            specBandX[i] = gridLeft + (i.toFloat() / (NUM_SPEC_BANDS - 1).toFloat()) * gridWidth
        }

        spectrumFillPaint.shader = LinearGradient(
            0f, gridTop, 0f, gridBottom,
            Color.parseColor("#3CE5A93C"),
            Color.parseColor("#00E5A93C"),
            Shader.TileMode.CLAMP
        )
    }

    private fun gainToY(gain: Float): Float {
        val clamped = gain.coerceIn(-10.0f, 10.0f)
        val norm = (clamped + 10.0f) / 20.0f
        return gridBottom - norm * gridHeight
    }

    private fun spectrumDbToY(db: Float): Float {
        val clamped = db.coerceIn(-52f, 0f)
        val norm = (clamped + 52f) / 52f
        return gridBottom - norm * (gridHeight * 0.96f)
    }

    private fun yToGain(y: Float): Float {
        val norm = (gridBottom - y) / gridHeight
        val rawGain = (norm * 20.0f) - 10.0f
        val stepped = (rawGain * 2.0f).roundToInt() / 2.0f
        return stepped.coerceIn(-10.0f, 10.0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gridWidth <= 0 || gridHeight <= 0) return

        // ★ スペクトラム表示値の更新はフレームコールバック側
        //   （advanceSpectrum）で行う。ここでは描くだけ。

        val x16k = labelX[9]
        canvas.drawRect(x16k, gridTop, gridRight, gridBottom, hiResZonePaint)

        val numHoriz = 20
        for (i in 0..numHoriz) {
            val y = gridTop + i * (gridHeight / numHoriz)
            if (i == numHoriz / 2) {
                canvas.drawLine(gridLeft, y, gridRight, y, centerLinePaint)
            } else {
                canvas.drawLine(gridLeft, y, gridRight, y, gridPaint)
            }
        }

        val labelY = height.toFloat() - 4f * density
        for (i in bandLabels.indices) {
            val x = labelX[i]
            canvas.drawLine(x, gridTop, x, gridBottom, gridPaint)
            val p = when {
                i == 10 -> hiResLabelPaint
                isEditMode && i == selectedBandIndex -> selectedLabelPaint
                else -> labelPaint
            }
            canvas.drawText(bandLabels[i], x, labelY, p)
        }

        canvas.drawRect(gridLeft, gridTop, gridRight, gridBottom, gridBorderPaint)

        if (isSpectrumEnabled) {
            val n = NUM_SPEC_BANDS
            val xSpec = FloatArray(n)
            val ySpec = FloatArray(n)
            for (i in 0 until n) {
                xSpec[i] = specBandX[i]
                ySpec[i] = spectrumDbToY(currentSpectrum[i])
            }

            val dS = FloatArray(n)
            val mS = FloatArray(n - 1)
            for (i in 0 until n - 1) {
                val dx = xSpec[i + 1] - xSpec[i]
                mS[i] = if (dx > 0f) (ySpec[i + 1] - ySpec[i]) / dx else 0f
            }
            dS[0] = mS[0]
            dS[n - 1] = mS[n - 2]
            for (i in 1 until n - 1) {
                if (mS[i - 1] * mS[i] <= 0f) {
                    dS[i] = 0f
                } else {
                    dS[i] = (mS[i - 1] + mS[i]) * 0.5f
                }
            }

            spectrumPath.reset()
            spectrumPath.moveTo(xSpec[0], ySpec[0])
            for (i in 0 until n - 1) {
                val hx = xSpec[i + 1] - xSpec[i]
                val steps = 12
                for (step in 1..steps) {
                    val t = step.toFloat() / steps
                    val t2 = t * t
                    val t3 = t2 * t
                    val h00 = 2 * t3 - 3 * t2 + 1
                    val h10 = t3 - 2 * t2 + t
                    val h01 = -2 * t3 + 3 * t2
                    val h11 = t3 - t2
                    val px = xSpec[i] + t * hx
                    val py = h00 * ySpec[i] + h10 * hx * dS[i] + h01 * ySpec[i + 1] + h11 * hx * dS[i + 1]
                    spectrumPath.lineTo(px, py.coerceIn(gridTop, gridBottom))
                }
            }

            spectrumFillPath.reset()
            spectrumFillPath.addPath(spectrumPath)
            spectrumFillPath.lineTo(gridRight, gridBottom)
            spectrumFillPath.lineTo(gridLeft, gridBottom)
            spectrumFillPath.close()

            canvas.drawPath(spectrumFillPath, spectrumFillPaint)
            canvas.drawPath(spectrumPath, spectrumLinePaint)
        }

        if (isEditMode && !isDirectBypass && selectedBandIndex in 0..9) {
            val curX = eqBandX[selectedBandIndex]
            canvas.drawLine(curX, gridTop, curX, gridBottom, cursorLinePaint)
        }

        curvePath.reset()
        val n = 10
        val xArr = FloatArray(n)
        val yArr = FloatArray(n)
        for (i in 0 until n) {
            xArr[i] = eqBandX[i]
            yArr[i] = gainToY(if (isDirectBypass) 0f else gains[i])
        }

        val d = FloatArray(n)
        val m = FloatArray(n - 1)
        for (i in 0 until n - 1) {
            val dx = xArr[i + 1] - xArr[i]
            m[i] = if (dx > 0f) (yArr[i + 1] - yArr[i]) / dx else 0f
        }
        d[0] = m[0]
        d[n - 1] = m[n - 2]
        for (i in 1 until n - 1) {
            if (m[i - 1] * m[i] <= 0f) {
                d[i] = 0f
            } else {
                d[i] = (m[i - 1] + m[i]) * 0.5f
            }
        }

        curvePath.moveTo(xArr[0], yArr[0])
        for (i in 0 until n - 1) {
            val hx = xArr[i + 1] - xArr[i]
            val steps = 20
            for (step in 1..steps) {
                val t = step.toFloat() / steps
                val t2 = t * t
                val t3 = t2 * t
                val h00 = 2 * t3 - 3 * t2 + 1
                val h10 = t3 - 2 * t2 + t
                val h01 = -2 * t3 + 3 * t2
                val h11 = t3 - t2
                val px = xArr[i] + t * hx
                val py = h00 * yArr[i] + h10 * hx * d[i] + h01 * yArr[i + 1] + h11 * hx * d[i + 1]
                curvePath.lineTo(px, py.coerceIn(gridTop, gridBottom))
            }
        }
        curvePath.lineTo(gridRight, yArr[n - 1])

        canvas.drawPath(curvePath, if (isDirectBypass) bypassCurvePaint else curvePaint)

        if (isEditMode && !isDirectBypass) {
            for (i in 0 until n) {
                val x = xArr[i]
                val y = yArr[i]
                if (i == selectedBandIndex) {
                    pointPaint.color = ContextCompat.getColor(context, R.color.accent)
                    canvas.drawCircle(x, y, 5.0f * density, pointPaint)
                    pointPaint.color = if (isLightMode) Color.WHITE else Color.parseColor("#121212")
                    canvas.drawCircle(x, y, 2.0f * density, pointPaint)
                    pointPaint.color = if (isLightMode) ContextCompat.getColor(context, R.color.text_primary_light) else Color.WHITE
                } else {
                    pointPaint.color = if (isLightMode) ContextCompat.getColor(context, R.color.text_primary_light) else Color.WHITE
                    canvas.drawCircle(x, y, 3.0f * density, pointPaint)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditMode || isDirectBypass) return false

        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                var closestIdx = 0
                var minDiff = Float.MAX_VALUE
                for (i in eqBandX.indices) {
                    val diff = abs(x - eqBandX[i])
                    if (diff < minDiff) {
                        minDiff = diff
                        closestIdx = i
                    }
                }

                activeDragBandIndex = closestIdx
                selectedBandIndex = closestIdx

                val newGain = yToGain(y)
                if (gains[activeDragBandIndex] != newGain) {
                    gains[activeDragBandIndex] = newGain
                    onGainChangedListener?.invoke(activeDragBandIndex, newGain, gains)
                }
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeDragBandIndex in 0..9) {
                    val newGain = yToGain(y)
                    if (gains[activeDragBandIndex] != newGain) {
                        gains[activeDragBandIndex] = newGain
                        onGainChangedListener?.invoke(activeDragBandIndex, newGain, gains)
                    }
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeDragBandIndex = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun stepGain(delta: Float) {
        if (selectedBandIndex in 0..9 && !isDirectBypass) {
            val newGain = (gains[selectedBandIndex] + delta).coerceIn(-10.0f, 10.0f)
            gains[selectedBandIndex] = newGain
            invalidate()
            onGainChangedListener?.invoke(selectedBandIndex, newGain, gains)
        }
    }

    fun resetAllFlat() {
        gains.fill(0.0f)
        invalidate()
        onGainChangedListener?.invoke(selectedBandIndex, 0.0f, gains)
    }
}