package com.retroamp.player

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import android.graphics.drawable.GradientDrawable
import kotlin.math.min

/** Custom-painted metal/glass surfaces: no external artwork dependencies. */
class NeonSkin(private val kind: Kind, private val density: Float) : Drawable() {
    enum class Kind { PANEL, BUTTON, ACTIVE, PLAY, HEADER }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun d(v: Float) = v * density

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val rect = RectF(b.left.toFloat()+d(1f), b.top.toFloat()+d(1f), b.right.toFloat()-d(1f), b.bottom.toFloat()-d(1f))
        val round = if (kind == Kind.PLAY) min(w,h)/2f else d(if (kind == Kind.PANEL) 16f else 10f)
        val colors = when(kind) {
            Kind.PANEL -> intArrayOf(Color.rgb(43,52,56),Color.rgb(14,23,26),Color.rgb(4,10,13))
            Kind.HEADER -> intArrayOf(Color.rgb(54,62,68),Color.rgb(13,19,23),Color.rgb(5,10,13))
            Kind.ACTIVE -> intArrayOf(Color.rgb(27,139,64),Color.rgb(8,69,39),Color.rgb(4,25,21))
            Kind.PLAY -> intArrayOf(Color.rgb(44,75,72),Color.rgb(8,28,29),Color.rgb(2,9,12))
            Kind.BUTTON -> intArrayOf(Color.rgb(70,78,83),Color.rgb(31,38,42),Color.rgb(8,14,18))
        }
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(rect.left,rect.top,rect.left,rect.bottom,colors,null,Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect,round,round,p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(if(kind == Kind.PLAY) 3f else 1.2f)
        p.color = if(kind == Kind.PLAY || kind == Kind.ACTIVE) Color.rgb(76,255,95) else Color.rgb(68,126,104)
        canvas.drawRoundRect(rect,round,round,p)
        p.color = Color.argb(110,200,255,220)
        p.strokeWidth = d(0.7f)
        val highlight = RectF(rect.left+d(4f),rect.top+d(3f),rect.right-d(4f),rect.bottom-d(4f))
        canvas.save()
        canvas.clipRect(rect.left,rect.top,rect.right,rect.top+h*0.33f)
        canvas.drawRoundRect(highlight,round,round,p)
        canvas.restore()
        if(kind == Kind.PLAY || kind == Kind.ACTIVE) {
            p.color = Color.argb(70,80,255,110)
            p.strokeWidth = d(5f)
            canvas.drawRoundRect(rect,round,round,p)
        }
        // Brushed aluminum grain and engraved trim, clipped to the metal shell.
        if (kind != Kind.PLAY) {
            canvas.save()
            canvas.clipRect(rect)
            p.style = Paint.Style.STROKE
            p.strokeWidth = d(0.45f)
            var y = rect.top + d(5f)
            var row = 0
            while (y < rect.bottom - d(5f)) {
                p.color = if (row % 3 == 0) Color.argb(19, 225, 244, 235) else Color.argb(14, 0, 0, 0)
                canvas.drawLine(rect.left + d(5f), y, rect.right - d(5f), y, p)
                y += d(3.5f)
                row++
            }
            canvas.restore()
            // Small inset metal fasteners on the larger equipment chassis.
            if (kind == Kind.HEADER || kind == Kind.PANEL) {
                val rr = d(2.6f)
                val screws = arrayOf(
                    Pair(rect.left + d(10f), rect.top + d(10f)),
                    Pair(rect.right - d(10f), rect.top + d(10f)),
                    Pair(rect.left + d(10f), rect.bottom - d(10f)),
                    Pair(rect.right - d(10f), rect.bottom - d(10f))
                )
                for ((cx, cy) in screws) {
                    p.style = Paint.Style.FILL
                    p.shader = LinearGradient(cx - rr, cy - rr, cx + rr, cy + rr,
                        intArrayOf(Color.rgb(180, 194, 191), Color.rgb(60, 72, 75), Color.rgb(12, 20, 24)),
                        null, Shader.TileMode.CLAMP)
                    canvas.drawCircle(cx, cy, rr, p)
                    p.shader = null
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = d(0.75f)
                    p.color = Color.rgb(14, 22, 25)
                    canvas.drawLine(cx - rr * 0.45f, cy + rr * 0.45f, cx + rr * 0.45f, cy - rr * 0.45f, p)
                }
            }
        } else {
            // Three concentric machined rings and an inner glass lens.
            val cx = rect.centerX()
            val cy = rect.centerY()
            val radius = min(rect.width(), rect.height()) / 2f
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = d(2f)
            p.color = Color.rgb(180, 205, 199)
            canvas.drawCircle(cx, cy, radius - d(6f), p)
            p.strokeWidth = d(1.4f)
            p.color = Color.rgb(19, 77, 56)
            canvas.drawCircle(cx, cy, radius - d(10f), p)
            p.strokeWidth = d(1f)
            p.color = Color.argb(180, 88, 255, 119)
            canvas.drawArc(RectF(cx-radius+d(3f),cy-radius+d(3f),cx+radius-d(3f),cy+radius-d(3f)),
                202f, 132f, false, p)
        }
        p.shader = null
        p.style = Paint.Style.FILL
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { p.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
