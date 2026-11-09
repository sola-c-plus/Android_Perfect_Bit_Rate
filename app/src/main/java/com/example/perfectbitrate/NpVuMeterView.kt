package com.example.perfectbitrate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ★ Local 再生画面（Now Playing）の表示切替用 アナログ VU メーター
//   View。PR #85 の実機フィードバックを受け、L/R のセグメント
//   バー型から針式アナログメーターへ作り替えた。L/R 2つの盤面を
//   上下に並べ、各盤面は円弧状の dB スケール（0dB 手前から赤
//   ゾーン）、下部中央の軸から伸びる針、弧の外側に付くピーク
//   ホールドの印で構成する。データ契約はヘッダー用の
//   WalkmanLevelMeterView と同じ `setLevels(dbL, dbR)` で、
//   バリスティクスも同一定数を踏襲する（アタックは即時、戻り
//   140dB/s、ピークホールド 750ms → 減衰 60dB/s、dB スケールの
//   折れ線マッピングも同一）。供給される値は再生ヘッド整列済み
//   のチャンクピーク dB で、厳密な VU（平均レベル）ではない
//   （動きはピークメーター寄り）。
class NpVuMeterView @JvmOverloads constructor(
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

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val faceBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val redZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * density
        textAlign = Paint.Align.CENTER
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * density
        textAlign = Paint.Align.CENTER
    }
    private val infinityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f * density
        textAlign = Paint.Align.CENTER
    }
    private val channelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13.0f * density
        textAlign = Paint.Align.LEFT
    }
    private val vuPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12.0f * density
        textAlign = Paint.Align.CENTER
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val hubInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
    }

    // 目盛り（ラベル, dB）。数字は弧の内側に置く。-3dB は
    // 赤ゾーンの開始点として目盛りだけ別に引く（ラベルなし、
    // 右端は数字が密になるため）。
    private val scaleMarks = listOf(
        Pair("-∞", -50f),
        Pair("-40", -40f),
        Pair("-30", -30f),
        Pair("-20", -20f),
        Pair("-10", -10f),
        Pair("-5", -5f),
        Pair("0", 0f)
    )
    private val redZoneStartDb = -3f

    // 針の振れ角: dbToFraction の 0..1 を -40°..+40° へ写す
    // （真上が 0、右が +）。円弧の描画角はこれと 90° ずれる。
    private val sweepHalfDeg = 40f

    private var targetDbL = -60f
    private var targetDbR = -60f
    private var currentDbL = -60f
    private var currentDbR = -60f

    private var peakHoldDbL = -60f
    private var peakHoldDbR = -60f
    private var peakHoldTimeL = 0L
    private var peakHoldTimeR = 0L
    private val peakHoldMs = 750L
    private val peakDecayRate = 60f

    private var lastDrawTime = 0L
    private val decayRateDbPerSec = 140f

    // 盤面1つ分の幾何（onSizeChanged で確定させる）。
    private class MeterGeometry {
        val face = RectF()
        var centerX = 0f
        var pivotY = 0f
        var radius = 0f
        val markFractions = ArrayList<Float>()
    }

    private val geometryL = MeterGeometry()
    private val geometryR = MeterGeometry()
    private var hasGeometry = false

    init {
        try {
            val monoTf = ResourcesCompat.getFont(context, R.font.roboto_mono_bold)
            val condensedTf = ResourcesCompat.getFont(context, R.font.roboto_condensed_bold)
            if (monoTf != null) {
                numberPaint.typeface = monoTf
                zeroPaint.typeface = monoTf
                channelPaint.typeface = monoTf
            }
            if (condensedTf != null) {
                infinityPaint.typeface = condensedTf
                vuPaint.typeface = condensedTf
            }
        } catch (e: Exception) {}
        updatePaintsForTheme()
    }

    private fun updatePaintsForTheme() {
        if (isLightMode) {
            facePaint.color = ContextCompat.getColor(context, R.color.surface_panel_light)
            faceBorderPaint.color = ContextCompat.getColor(context, R.color.component_border_light)
            arcPaint.color = ContextCompat.getColor(context, R.color.accent_light)
            tickPaint.color = ContextCompat.getColor(context, R.color.text_primary_light)
            numberPaint.color = ContextCompat.getColor(context, R.color.text_secondary_light)
            infinityPaint.color = ContextCompat.getColor(context, R.color.text_secondary_light)
            channelPaint.color = ContextCompat.getColor(context, R.color.text_primary_light)
            vuPaint.color = ContextCompat.getColor(context, R.color.accent_light)
            needlePaint.color = ContextCompat.getColor(context, R.color.text_primary_light)
            hubPaint.color = ContextCompat.getColor(context, R.color.accent_light)
            hubInnerPaint.color = ContextCompat.getColor(context, R.color.main_background_light)
            peakPaint.color = ContextCompat.getColor(context, R.color.danger_light)
        } else {
            facePaint.color = ContextCompat.getColor(context, R.color.surface_panel)
            faceBorderPaint.color = ContextCompat.getColor(context, R.color.grid_border)
            arcPaint.color = ContextCompat.getColor(context, R.color.accent)
            tickPaint.color = ContextCompat.getColor(context, R.color.text_primary)
            numberPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
            infinityPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
            channelPaint.color = ContextCompat.getColor(context, R.color.text_primary)
            vuPaint.color = ContextCompat.getColor(context, R.color.accent)
            needlePaint.color = ContextCompat.getColor(context, R.color.text_primary)
            hubPaint.color = ContextCompat.getColor(context, R.color.accent)
            hubInnerPaint.color = ContextCompat.getColor(context, R.color.main_background)
            peakPaint.color = ContextCompat.getColor(context, R.color.danger)
        }
        redZonePaint.color = ContextCompat.getColor(context, R.color.danger_meter)
        zeroPaint.color = ContextCompat.getColor(context, R.color.danger_meter)
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
        if (w <= 0 || h <= 0) {
            hasGeometry = false
            return
        }
        val gap = 10f * density
        val meterHeight = (h - gap) / 2f
        layoutMeter(geometryL, 0f, meterHeight, w.toFloat())
        layoutMeter(geometryR, meterHeight + gap, meterHeight, w.toFloat())
        hasGeometry = geometryL.radius > 0f && geometryR.radius > 0f
    }

    private fun layoutMeter(g: MeterGeometry, top: Float, height: Float, width: Float) {
        val inset = 2f * density
        g.face.set(inset, top + inset, width - inset, top + height - inset)
        g.centerX = width / 2f
        g.pivotY = g.face.bottom - 12f * density
        // 弧の半径: 幅基準（ピーク印の分も内側に収める）と
        // 高さ基準（弧の上端＋数字の余白）の小さい方。
        val radiusByWidth = g.centerX - g.face.left - 18f * density
        val radiusByHeight = g.pivotY - g.face.top - 30f * density
        g.radius = min(radiusByWidth, radiusByHeight)
        g.markFractions.clear()
        for (mark in scaleMarks) {
            g.markFractions.add(dbToFraction(mark.second))
        }
    }

    // WalkmanLevelMeterView と同一の折れ線マッピング（-50dB→0、0dB→1）
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

    // 弧上の点（真上を 0 とする振れ角で引く）。
    private fun arcPoint(g: MeterGeometry, radius: Float, frac: Float): FloatArray {
        val angle = Math.toRadians((-sweepHalfDeg + 2f * sweepHalfDeg * frac).toDouble())
        return floatArrayOf(
            g.centerX + (radius * sin(angle)).toFloat(),
            g.pivotY - (radius * cos(angle)).toFloat()
        )
    }

    private fun centeredBaseline(paint: Paint, centerY: Float): Float {
        val fm = paint.fontMetrics
        return centerY - (fm.descent + fm.ascent) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasGeometry) return

        val now = System.currentTimeMillis()
        if (lastDrawTime > 0L) {
            val dt = (now - lastDrawTime) / 1000f
            if (currentDbL > targetDbL) {
                currentDbL = max(targetDbL, currentDbL - decayRateDbPerSec * dt)
            }
            if (currentDbR > targetDbR) {
                currentDbR = max(targetDbR, currentDbR - decayRateDbPerSec * dt)
            }
            if (now - peakHoldTimeL > peakHoldMs && peakHoldDbL > -60f) {
                peakHoldDbL = max(-60f, peakHoldDbL - peakDecayRate * dt)
            }
            if (now - peakHoldTimeR > peakHoldMs && peakHoldDbR > -60f) {
                peakHoldDbR = max(-60f, peakHoldDbR - peakDecayRate * dt)
            }
        }
        lastDrawTime = now

        drawMeter(canvas, geometryL, "L", currentDbL, peakHoldDbL)
        drawMeter(canvas, geometryR, "R", currentDbR, peakHoldDbR)

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

    private fun drawMeter(
        canvas: Canvas,
        g: MeterGeometry,
        channelLabel: String,
        currentDb: Float,
        peakDb: Float
    ) {
        val corner = 10f * density
        canvas.drawRoundRect(g.face, corner, corner, facePaint)
        canvas.drawRoundRect(g.face, corner, corner, faceBorderPaint)

        val r = g.radius
        // 弧: 振れ角 -40°..+40° は drawArc の角度では -130° から
        // 80° 分（drawArc は3時方向が 0°・時計回りが正）。
        val oval = RectF(g.centerX - r, g.pivotY - r, g.centerX + r, g.pivotY + r)
        canvas.drawArc(oval, -sweepHalfDeg - 90f, 2f * sweepHalfDeg, false, arcPaint)
        val redStartDeg = -sweepHalfDeg + 2f * sweepHalfDeg * dbToFraction(redZoneStartDb)
        canvas.drawArc(oval, redStartDeg - 90f, sweepHalfDeg - redStartDeg, false, redZonePaint)

        // 目盛りと数字（数字は弧の内側）。
        for (i in scaleMarks.indices) {
            val frac = g.markFractions[i]
            val tickInner = arcPoint(g, r - 4.5f * density, frac)
            val tickOuter = arcPoint(g, r + 1.5f * density, frac)
            canvas.drawLine(tickInner[0], tickInner[1], tickOuter[0], tickOuter[1], tickPaint)
            val label = scaleMarks[i].first
            val paint = when (label) {
                "0" -> zeroPaint
                "-∞" -> infinityPaint
                else -> numberPaint
            }
            val numPos = arcPoint(g, r - 13.5f * density, frac)
            canvas.drawText(label, numPos[0], centeredBaseline(paint, numPos[1]), paint)
        }
        // 赤ゾーン開始（-3dB）の目盛りだけ追加で引く。
        val redFrac = dbToFraction(redZoneStartDb)
        val redInner = arcPoint(g, r - 3.5f * density, redFrac)
        val redOuter = arcPoint(g, r + 1.5f * density, redFrac)
        canvas.drawLine(redInner[0], redInner[1], redOuter[0], redOuter[1], tickPaint)

        // 盤面の字入れ: 左上にチャンネル名、中央に VU。
        val chFm = channelPaint.fontMetrics
        canvas.drawText(
            channelLabel,
            g.face.left + 10f * density,
            g.face.top + 9f * density - chFm.ascent,
            channelPaint
        )
        val vuCenterY = g.pivotY - r * 0.42f
        canvas.drawText("VU", g.centerX, centeredBaseline(vuPaint, vuCenterY), vuPaint)

        // ピークホールドの印（弧の外側の短い線）。
        if (peakDb > -48f) {
            val peakInner = arcPoint(g, r + 3.5f * density, dbToFraction(peakDb))
            val peakOuter = arcPoint(g, r + 8.5f * density, dbToFraction(peakDb))
            canvas.drawLine(peakInner[0], peakInner[1], peakOuter[0], peakOuter[1], peakPaint)
        }

        // 針（軸の後ろ側に短い尾付き）と中心軸。
        val frac = dbToFraction(currentDb)
        val tip = arcPoint(g, r - 6f * density, frac)
        val angle = Math.toRadians((-sweepHalfDeg + 2f * sweepHalfDeg * frac).toDouble())
        val tailX = g.centerX - (12f * density * sin(angle)).toFloat()
        val tailY = g.pivotY + (12f * density * cos(angle)).toFloat()
        canvas.drawLine(tailX, tailY, tip[0], tip[1], needlePaint)
        canvas.drawCircle(g.centerX, g.pivotY, 5f * density, hubPaint)
        canvas.drawCircle(g.centerX, g.pivotY, 2f * density, hubInnerPaint)
    }
}
