package com.example.perfectbitrate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

// ★ Local 再生画面（Now Playing）の表示切替用スペクトラム View。
//   DSP ダイアログの EQ 背面スペクトラム（WalkmanEqView）と同じ
//   ゴールド（accent #E5A93C）の線＋グラデーション塗りで、データは
//   既存の32バンド・再生ヘッド整列スナップショットをそのまま使う。
//   時間方向の平滑化も WalkmanEqView と同じ方式（目標値だけ保持し、
//   フレーム周期で攻撃側は時定数つき指数平滑、減衰側は 75 dB/s）。
//   EQ カーブ・グリッド・編集操作は持たない軽量版。
class NpSpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val NUM_BANDS = 32
    }

    private val density = resources.displayMetrics.density

    var isLightMode = false
        set(value) {
            field = value
            updatePaintsForTheme()
            invalidate()
        }

    // ★ 表示中（選択中）かどうか。MainActivity が切替に合わせて
    //   設定し、フレームアニメーションは表示中だけ回す。
    var active = false
        set(value) {
            field = value
            if (value) startAnimation() else stopAnimation()
        }

    private val targetSpectrum = FloatArray(NUM_BANDS) { -55f }
    private val currentSpectrum = FloatArray(NUM_BANDS) { -55f }

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
            if (advanceSpectrum(dt)) {
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun advanceSpectrum(dt: Float): Boolean {
        val attackFactor = 1f - exp(-dt / SPECTRUM_ATTACK_TAU_SEC)
        var moving = false
        for (i in 0 until NUM_BANDS) {
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

    private fun startAnimation() {
        if (spectrumAnimRunning || !isAttachedToWindow) return
        spectrumAnimRunning = true
        lastSpectrumFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(spectrumFrameCallback)
    }

    private fun stopAnimation() {
        spectrumAnimRunning = false
        Choreographer.getInstance().removeFrameCallback(spectrumFrameCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && active) {
            startAnimation()
        } else if (visibility != VISIBLE) {
            stopAnimation()
        }
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 0.75f * density
    }
    private val gridBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.0f * density
        style = Paint.Style.STROKE
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
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8.5f * density
        textAlign = Paint.Align.CENTER
    }

    private var gridLeft = 0f
    private var gridRight = 0f
    private var gridTop = 0f
    private var gridBottom = 0f
    private var gridHeight = 0f
    private var gridWidth = 0f
    private val specBandX = FloatArray(NUM_BANDS)

    // ラベルを置くバンド番号と表記（32バンドは約1/3オクターブ間隔）
    private val labelBands = intArrayOf(0, 6, 12, 18, 24, 30)
    private val labelTexts = arrayOf("31", "125", "500", "2K", "8K", "32K")

    private val spectrumPath = Path()
    private val spectrumFillPath = Path()

    init {
        try {
            val condensedTf = ResourcesCompat.getFont(context, R.font.roboto_condensed_bold)
            if (condensedTf != null) {
                labelPaint.typeface = condensedTf
            }
        } catch (e: Exception) {}
        updatePaintsForTheme()
    }

    private fun updatePaintsForTheme() {
        if (isLightMode) {
            gridPaint.color = ContextCompat.getColor(context, R.color.component_inactive_light)
            gridBorderPaint.color = ContextCompat.getColor(context, R.color.component_border_light)
            labelPaint.color = ContextCompat.getColor(context, R.color.label_gray_light)
        } else {
            gridPaint.color = ContextCompat.getColor(context, R.color.divider)
            gridBorderPaint.color = ContextCompat.getColor(context, R.color.grid_border)
            labelPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
        }
    }

    fun setSpectrumLevels(levels: FloatArray) {
        val count = min(levels.size, NUM_BANDS)
        for (i in 0 until count) {
            val safeDb = if (levels[i].isNaN() || levels[i].isInfinite()) {
                -55f
            } else {
                levels[i].coerceIn(-55f, 0f)
            }
            targetSpectrum[i] = safeDb
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val labelAreaHeight = 18f * density
        val topPadding = 6f * density
        val sidePadding = 6f * density

        gridLeft = sidePadding
        gridRight = w.toFloat() - sidePadding
        gridTop = topPadding
        gridBottom = h.toFloat() - labelAreaHeight
        gridWidth = gridRight - gridLeft
        gridHeight = gridBottom - gridTop

        for (i in 0 until NUM_BANDS) {
            specBandX[i] = gridLeft + (i.toFloat() / (NUM_BANDS - 1).toFloat()) * gridWidth
        }

        spectrumFillPaint.shader = LinearGradient(
            0f, gridTop, 0f, gridBottom,
            Color.parseColor("#3CE5A93C"),
            Color.parseColor("#00E5A93C"),
            Shader.TileMode.CLAMP
        )
    }

    private fun spectrumDbToY(db: Float): Float {
        val clamped = db.coerceIn(-52f, 0f)
        val norm = (clamped + 52f) / 52f
        return gridBottom - norm * (gridHeight * 0.96f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gridWidth <= 0 || gridHeight <= 0) return

        // 横グリッド（約 -13dB 刻みの4本）と枠
        val numHoriz = 4
        for (i in 0..numHoriz) {
            val y = gridTop + i * (gridHeight / numHoriz)
            canvas.drawLine(gridLeft, y, gridRight, y, gridPaint)
        }
        canvas.drawRect(gridLeft, gridTop, gridRight, gridBottom, gridBorderPaint)

        // スペクトラム本体（線＋グラデーション塗り）
        spectrumPath.reset()
        for (i in 0 until NUM_BANDS) {
            val x = specBandX[i]
            val y = spectrumDbToY(currentSpectrum[i])
            if (i == 0) spectrumPath.moveTo(x, y) else spectrumPath.lineTo(x, y)
        }
        spectrumFillPath.reset()
        spectrumFillPath.addPath(spectrumPath)
        spectrumFillPath.lineTo(gridRight, gridBottom)
        spectrumFillPath.lineTo(gridLeft, gridBottom)
        spectrumFillPath.close()
        canvas.drawPath(spectrumFillPath, spectrumFillPaint)
        canvas.drawPath(spectrumPath, spectrumLinePaint)

        // 周波数ラベル
        val labelY = height.toFloat() - 4f * density
        for (i in labelBands.indices) {
            canvas.drawText(labelTexts[i], specBandX[labelBands[i]], labelY, labelPaint)
        }
    }
}
