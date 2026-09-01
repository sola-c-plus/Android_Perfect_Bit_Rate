package com.example.perfectbitrate

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class WalkmanEqView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    val bandLabels = arrayOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K")
    val gains = FloatArray(10) { 0.0f }

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

    var selectedBandIndex = 7 // デフォルト: 4K
        set(value) {
            field = value.coerceIn(0, 9)
            invalidate()
            onBandSelectedListener?.invoke(field, gains[field])
        }

    var onGainChangedListener: ((Int, Float, FloatArray) -> Unit)? = null
    var onBandSelectedListener: ((Int, Float) -> Unit)? = null

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#181818")
        strokeWidth = 0.8f * density
    }

    private val gridBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#282828")
        strokeWidth = 1.0f * density
        style = Paint.Style.STROKE
    }

    private val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        strokeWidth = 1.0f * density
    }

    private val cursorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        strokeWidth = 1.0f * density
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

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#777777")
        textSize = 8.5f * density
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val selectedLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        textSize = 9.0f * density
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private var gridLeft = 0f
    private var gridRight = 0f
    private var gridTop = 0f
    private var gridBottom = 0f
    private var gridHeight = 0f
    private var gridWidth = 0f

    private val bandX = FloatArray(10)
    private val curvePath = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        // ★ 横長になりすぎないよう、左右に美しいマージンを確保して実機のアスペクト比に調整
        val maxGridW = min(w.toFloat() - 48f * density, 340f * density)
        val horizontalMargin = (w.toFloat() - maxGridW) / 2f

        gridLeft = horizontalMargin
        gridRight = w.toFloat() - horizontalMargin
        gridTop = 6f * density
        gridBottom = h.toFloat() - 18f * density
        gridWidth = gridRight - gridLeft
        gridHeight = gridBottom - gridTop

        val stepX = gridWidth / (bandLabels.size - 1)
        for (i in bandLabels.indices) {
            bandX[i] = gridLeft + i * stepX
        }
    }

    private fun gainToY(gain: Float): Float {
        val clamped = gain.coerceIn(-10.0f, 10.0f)
        val norm = (clamped + 10.0f) / 20.0f
        return gridBottom - norm * gridHeight
    }

    private fun yToGain(y: Float): Float {
        val norm = (gridBottom - y) / gridHeight
        val rawGain = (norm * 20.0f) - 10.0f
        val stepped = (rawGain * 2.0f).roundToInt() / 2.0f
        return stepped.coerceIn(-10.0f, 10.0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gridWidth <= 0) return

        // 1. 水平グリッド線 (2dB おき = 10分割、11本)
        val numHoriz = 10
        for (i in 0..numHoriz) {
            val y = gridTop + i * (gridHeight / numHoriz)
            if (i == numHoriz / 2) {
                canvas.drawLine(gridLeft, y, gridRight, y, centerLinePaint) // 0dB センターライン
            } else {
                canvas.drawLine(gridLeft, y, gridRight, y, gridPaint)
            }
        }

        // 2. 垂直グリッド線 & 周波数ラベル
        val labelY = height.toFloat() - 3f * density
        for (i in bandLabels.indices) {
            val x = bandX[i]
            canvas.drawLine(x, gridTop, x, gridBottom, gridPaint)
            val p = if (isEditMode && i == selectedBandIndex) selectedLabelPaint else labelPaint
            canvas.drawText(bandLabels[i], x, labelY, p)
        }

        // 外枠
        canvas.drawRect(gridLeft, gridTop, gridRight, gridBottom, gridBorderPaint)

        // 3. 編集モード時: 選択中バンドの垂直カーソル線
        if (isEditMode && !isDirectBypass && selectedBandIndex in 0..9) {
            val curX = bandX[selectedBandIndex]
            canvas.drawLine(curX, gridTop, curX, gridBottom, cursorLinePaint)
        }

        // 4. Monotone Cubic Spline (実機さながらの不要な波打ちのない美しい曲線)
        curvePath.reset()
        val n = 10
        val xArr = FloatArray(n)
        val yArr = FloatArray(n)
        for (i in 0 until n) {
            xArr[i] = bandX[i]
            yArr[i] = gainToY(if (isDirectBypass) 0f else gains[i])
        }

        // 傾き計算 (Monotone Spline / Fritsch-Carlson)
        val d = FloatArray(n)
        val m = FloatArray(n - 1)
        for (i in 0 until n - 1) {
            m[i] = (yArr[i + 1] - yArr[i]) / (xArr[i + 1] - xArr[i])
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
            val steps = 16
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

        canvas.drawPath(curvePath, if (isDirectBypass) bypassCurvePaint else curvePaint)

        // 5. ★ まるぽちょ (編集モード時のみ描画！確定時は完全消灯)
        if (isEditMode && !isDirectBypass) {
            for (i in 0 until n) {
                val x = xArr[i]
                val y = yArr[i]
                if (i == selectedBandIndex) {
                    // 選択中の白丸 + 内側ブラックリング
                    pointPaint.color = Color.WHITE
                    canvas.drawCircle(x, y, 5.0f * density, pointPaint)
                    pointPaint.color = Color.parseColor("#121212")
                    canvas.drawCircle(x, y, 2.0f * density, pointPaint)
                    pointPaint.color = Color.WHITE
                } else {
                    // 通常の白丸
                    pointPaint.color = Color.WHITE
                    canvas.drawCircle(x, y, 3.0f * density, pointPaint)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditMode || isDirectBypass) {
            return false
        }

        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                var closestIdx = 0
                var minDiff = Float.MAX_VALUE
                for (i in bandX.indices) {
                    val diff = abs(x - bandX[i])
                    if (diff < minDiff) {
                        minDiff = diff
                        closestIdx = i
                    }
                }

                selectedBandIndex = closestIdx
                val newGain = yToGain(y)
                if (gains[closestIdx] != newGain) {
                    gains[closestIdx] = newGain
                    onGainChangedListener?.invoke(closestIdx, newGain, gains)
                }
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
