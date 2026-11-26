package vn.savify.karaoke

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Icon ve bang code (khong phu thuoc font, khong bi o vuong tren TV). Khung 24x24. */
class IconView(ctx: Context, var kind: String, var color: Int = Color.WHITE) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        fill.style = Paint.Style.FILL
        line.style = Paint.Style.STROKE
        line.strokeWidth = 2.2f
        line.strokeCap = Paint.Cap.ROUND
        line.strokeJoin = Paint.Join.ROUND
    }

    fun set(k: String, c: Int = color) {
        kind = k
        color = c
        invalidate()
    }

    private fun tri(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        val p = Path()
        p.moveTo(x1, y1); p.lineTo(x2, y2); p.lineTo(x3, y3); p.close()
        c.drawPath(p, fill)
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) / 24f
        if (s <= 0f) return
        c.save()
        c.translate((width - 24 * s) / 2f, (height - 24 * s) / 2f)
        c.scale(s, s)
        fill.color = color
        line.color = color
        when (kind) {
            "play" -> tri(c, 7f, 4f, 20f, 12f, 7f, 20f)
            "pause" -> { c.drawRect(6f, 4f, 10.5f, 20f, fill); c.drawRect(13.5f, 4f, 18f, 20f, fill) }
            "next" -> { tri(c, 5f, 5f, 15f, 12f, 5f, 19f); c.drawRect(16.5f, 5f, 19.5f, 19f, fill) }
            "prev" -> { tri(c, 19f, 5f, 9f, 12f, 19f, 19f); c.drawRect(4.5f, 5f, 7.5f, 19f, fill) }
            "rew" -> { tri(c, 11f, 5f, 2.5f, 12f, 11f, 19f); tri(c, 21.5f, 5f, 13f, 12f, 21.5f, 19f) }
            "ff" -> { tri(c, 2.5f, 5f, 11f, 12f, 2.5f, 19f); tri(c, 13f, 5f, 21.5f, 12f, 13f, 19f) }
            "fullscreen" -> {
                val p = Path()
                p.moveTo(4f, 9f); p.lineTo(4f, 4f); p.lineTo(9f, 4f)
                p.moveTo(15f, 4f); p.lineTo(20f, 4f); p.lineTo(20f, 9f)
                p.moveTo(20f, 15f); p.lineTo(20f, 20f); p.lineTo(15f, 20f)
                p.moveTo(9f, 20f); p.lineTo(4f, 20f); p.lineTo(4f, 15f)
                c.drawPath(p, line)
            }
            "search" -> { c.drawCircle(10.5f, 10.5f, 6f, line); c.drawLine(15f, 15f, 20f, 20f, line) }
            "mic" -> {
                c.drawRoundRect(RectF(9f, 3f, 15f, 14f), 3f, 3f, fill)
                c.drawArc(RectF(5.5f, 6f, 18.5f, 19f), 0f, 180f, false, line)
                c.drawLine(12f, 19f, 12f, 22f, line)
            }
            "heart" -> {
                val h = Path()
                h.moveTo(12f, 21f)
                h.cubicTo(5f, 15.5f, 2.5f, 11.5f, 2.5f, 8.2f)
                h.cubicTo(2.5f, 5.5f, 4.6f, 3.5f, 7.2f, 3.5f)
                h.cubicTo(9.2f, 3.5f, 11f, 4.6f, 12f, 6.4f)
                h.cubicTo(13f, 4.6f, 14.8f, 3.5f, 16.8f, 3.5f)
                h.cubicTo(19.4f, 3.5f, 21.5f, 5.5f, 21.5f, 8.2f)
                h.cubicTo(21.5f, 11.5f, 19f, 15.5f, 12f, 21f)
                h.close()
                c.drawPath(h, fill)
            }
            "plus" -> { c.drawLine(12f, 5f, 12f, 19f, line); c.drawLine(5f, 12f, 19f, 12f, line) }
            "dot" -> c.drawCircle(12f, 12f, 6f, fill)
            "eq" -> { c.drawRect(4f, 11f, 8f, 20f, fill); c.drawRect(10f, 4f, 14f, 20f, fill); c.drawRect(16f, 8f, 20f, 20f, fill) }
            "note" -> {
                c.drawCircle(8f, 18f, 3.5f, fill)
                c.drawRect(10.5f, 4f, 12.5f, 18f, fill)
                tri(c, 12.5f, 4f, 19f, 8f, 12.5f, 10f)
            }
            "wifi" -> {
                for (r in floatArrayOf(4f, 8f, 12f)) {
                    c.drawArc(RectF(12f - r, 19f - r, 12f + r, 19f + r), -135f, 90f, false, line)
                }
                c.drawCircle(12f, 19f, 1.6f, fill)
            }
            "lan" -> {
                c.drawRect(4f, 5f, 20f, 13f, line)
                c.drawLine(12f, 13f, 12f, 18f, line)
                c.drawLine(7f, 18f, 17f, 18f, line)
            }
            "vdown", "vup" -> {
                c.drawRect(2.5f, 9f, 6.5f, 15f, fill)
                val sp = Path()
                sp.moveTo(6.5f, 9f); sp.lineTo(12f, 4.5f); sp.lineTo(12f, 19.5f); sp.lineTo(6.5f, 15f); sp.close()
                c.drawPath(sp, fill)
                c.drawLine(15f, 12f, 21.5f, 12f, line)
                if (kind == "vup") c.drawLine(18.25f, 8.75f, 18.25f, 15.25f, line)
            }
            "gear" -> {
                c.drawCircle(12f, 12f, 4f, line)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val x1 = 12f + (7f * Math.cos(a)).toFloat()
                    val y1 = 12f + (7f * Math.sin(a)).toFloat()
                    val x2 = 12f + (9.5f * Math.cos(a)).toFloat()
                    val y2 = 12f + (9.5f * Math.sin(a)).toFloat()
                    c.drawLine(x1, y1, x2, y2, line)
                }
            }
        }
        c.restore()
    }
}

/** Thanh tien trinh bo tron. */
class BarView(ctx: Context) : View(ctx) {
    var frac = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        p.color = 0xFF2A2A38.toInt()
        c.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, p)
        if (frac > 0f) {
            p.color = 0xFFFF1744.toInt()
            c.drawRoundRect(RectF(0f, 0f, maxOf(h, w * frac), h), h / 2, h / 2, p)
        }
    }
}
