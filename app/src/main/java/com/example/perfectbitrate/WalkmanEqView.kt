package com.example.perfectbitrate

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class WalkmanEqView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    // EQ操作用の10バンドラベル + 右端の40K(ハイレゾ超高域)ラベル
    val bandLabels = arrayOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K", "40K")
    val eqFrequencies = doubleArrayOf(31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    val gains = FloatArray(10) { 0.0f }

    // ★ 32バンド 超高精細スペクトル周波数（25Hz 〜 40,000Hz）
    val spectrumFrequencies = floatArrayOf(
        25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f,
        250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f, 2000f,
        2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f,
        28000f, 40000f
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
                targetSpectrum.fill(-50f)
                currentSpectrum.fill(-50f)
            }
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

    // 32バンド用 スペクトルデータバッファ
    private val targetSpectrum = FloatArray(NUM_SPEC_BANDS) { -50f }
    private val currentSpectrum = FloatArray(NUM_SPEC_BANDS) { -50f }
    private var lastSpectrumDrawTime = 0L
    private val spectrumDecayRate = 85f // 85 dB/sec の滑らかな減衰

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1C1C")
        strokeWidth = 0.75f * density
    }

    private val hiResZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#15E5A93C")
        style = Paint.Style.FILL
    }

    private val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3C3C3C")
        strokeWidth = 1.0f * density
    }

    private val gridBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E2E2E")
        strokeWidth = 1.0f * density
        style = Paint.Style.STROKE
    }

    private val cursorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E5A93C")
        strokeWidth = 1.2f * density
    }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        strokeWidth = 2.0f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val bypassCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#444444")
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    }

    private val spectrumLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E5A93C")
        strokeWidth = 1.6f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val spectrumFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888")
        textSize = 8.0f * density
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val hiResLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E5A93C")
        textSize = 8.0f * density
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val selectedLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        textSize = 8.5f * density
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private var gridLeft = 0f
    private var gridRight = 0f
    private var gridTop = 0f
    private var gridBottom = 0f
    private var gridHeight = 0f
    private var gridWidth = 0f

    private val eqBandX = FloatArray(10)
    private val labelX = FloatArray(11)
    private val specBandX = FloatArray(NUM_SPEC_BANDS)

    private val curvePath = Path()
    private val spectrumPath = Path()
    private val spectrumFillPath = Path()

    // 対数周波数マッピング (22Hz 〜 44kHz)
    private val logMinF = log10(22.0)
    private val logMaxF = log10(44000.0)

    private fun freqToX(freq: Double): Float {
        val f = freq.coerceIn(22.0, 44000.0)
        val norm = ((log10(f) - logMinF) / (logMaxF - logMinF)).toFloat().coerceIn(0f, 1f)
        return gridLeft + norm * gridWidth
    }

    fun setSpectrumLevels(levels: FloatArray) {
        if (!isSpectrumEnabled) return
        val count = min(levels.size, NUM_SPEC_BANDS)
        for (i in 0 until count) {
            val safeDb = if (levels[i].isNaN() || levels[i].isInfinite()) -50f else levels[i].coerceIn(-50f, 0f)
            targetSpectrum[i] = safeDb
            if (safeDb > currentSpectrum[i]) {
                currentSpectrum[i] = safeDb
            }
        }
        postInvalidateOnAnimation()
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

        // EQの10点X座標
        for (i in 0 until 10) {
            eqBandX[i] = freqToX(eqFrequencies[i])
            labelX[i] = eqBandX[i]
        }
        // 11番目: 40K ラベル位置
        labelX[10] = freqToX(40000.0)

        // 32バンドスペクトルX座標
        for (i in 0 until NUM_SPEC_BANDS) {
            specBandX[i] = freqToX(spectrumFrequencies[i].toDouble())
        }

        spectrumFillPaint.shader = LinearGradient(
            0f, gridTop, 0f, gridBottom,
            Color.parseColor("#3AE5A93C"),
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
        val clamped = db.coerceIn(-48f, 0f)
        val norm = (clamped + 48f) / 48f
        return gridBottom - norm * (gridHeight * 0.94f)
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

        // 減衰計算
        val now = System.currentTimeMillis()
        if (lastSpectrumDrawTime > 0L) {
            val dt = (now - lastSpectrumDrawTime) / 1000f
            for (i in 0 until NUM_SPEC_BANDS) {
                if (currentSpectrum[i] > targetSpectrum[i]) {
                    currentSpectrum[i] = max(targetSpectrum[i], currentSpectrum[i] - spectrumDecayRate * dt)
                }
            }
        }
        lastSpectrumDrawTime = now

        // ★ ハイレゾ超高域ゾーン (20kHz〜44kHz) の薄いゴールド背景ハイライト
        val x20k = freqToX(20000.0)
        canvas.drawRect(x20k, gridTop, gridRight, gridBottom, hiResZonePaint)

        // 水平グリッド線
        val numHoriz = 20
        for (i in 0..numHoriz) {
            val y = gridTop + i * (gridHeight / numHoriz)
            if (i == numHoriz / 2) {
                canvas.drawLine(gridLeft, y, gridRight, y, centerLinePaint)
            } else {
                canvas.drawLine(gridLeft, y, gridRight, y, gridPaint)
            }
        }

        // 垂直グリッド線 ＆ ラベル (31〜16K + 40K)
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

        // ★ 32バンド 超高精細スペクトル波形描画 (25Hz 〜 40kHz)
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

            var isStillDecaying = false
            for (i in 0 until NUM_SPEC_BANDS) {
                if (currentSpectrum[i] > targetSpectrum[i] + 0.1f) {
                    isStillDecaying = true
                    break
                }
            }
            if (isStillDecaying) {
                postInvalidateOnAnimation()
            }
        }

        // カーソルライン (調整中バンド)
        if (isEditMode && !isDirectBypass && selectedBandIndex in 0..9) {
            val curX = eqBandX[selectedBandIndex]
            canvas.drawLine(curX, gridTop, curX, gridBottom, cursorLinePaint)
        }

        // 10-Band EQ 曲線 (白線)
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
        // 16K以降を右端まで水平に自然延長
        curvePath.lineTo(gridRight, yArr[n - 1])

        canvas.drawPath(curvePath, if (isDirectBypass) bypassCurvePaint else curvePaint)

        // 調整用ドットポイント (10点)
        if (isEditMode && !isDirectBypass) {
            for (i in 0 until n) {
                val x = xArr[i]
                val y = yArr[i]
                if (i == selectedBandIndex) {
                    pointPaint.color = Color.parseColor("#E5A93C")
                    canvas.drawCircle(x, y, 5.0f * density, pointPaint)
                    pointPaint.color = Color.parseColor("#121212")
                    canvas.drawCircle(x, y, 2.0f * density, pointPaint)
                    pointPaint.color = Color.WHITE
                } else {
                    pointPaint.color = Color.WHITE
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