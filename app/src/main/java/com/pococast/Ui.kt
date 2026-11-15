package com.pococast

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.widget.TextView

/**
 * Design tokens borrowed from the leaving-cert study app: navy header, cyan
 * accent, warm paper, 12dp cards, uppercase eyebrows and 999dp pills.
 */
class Skin(private val context: Context) {
    fun color(id: Int): Int = context.getColor(id)
    val paper get() = color(R.color.paper)
    val card get() = color(R.color.card)
    val ink get() = color(R.color.ink)
    val muted get() = color(R.color.muted)
    val line get() = color(R.color.line)
    val navy get() = color(R.color.navy)
    val cyan get() = color(R.color.cyan)
    val cyanInk get() = color(R.color.cyan_ink)
}

fun Context.dp(value: Number): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
).toInt()

/** Filled or outlined rounded rectangle, optionally dashed. */
fun Context.rounded(
    fill: Int,
    radius: Float,
    stroke: Int = 0,
    strokeWidth: Float = 0f,
    dashed: Boolean = false
): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = radius
    when {
        dashed -> setStroke(
            dp(1),
            stroke,
            dp(5).toFloat(),
            dp(5).toFloat()
        )
        strokeWidth > 0f -> setStroke(strokeWidth.toInt().coerceAtLeast(1), stroke)
    }
}

fun Context.circle(fill: Int, stroke: Int = 0, strokeWidth: Float = 0f): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (strokeWidth > 0f) setStroke(strokeWidth.toInt().coerceAtLeast(1), stroke)
    }

fun Context.gradient(vararg colors: Int): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)

fun Context.ripple(content: Drawable, color: Int): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(color), content, null)

/** Text in the study app's voice: system sans, tabular numbers, tight leading. */
fun Context.label(
    text: CharSequence,
    sizeSp: Float,
    color: Int,
    medium: Boolean = false
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    includeFontPadding = false
}
