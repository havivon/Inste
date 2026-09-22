package com.havivon.reelslab.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

/** Keeps grid cells at the 9:16 shape reels are shot in. */
class AspectImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ImageView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredWidth * 16 / 9)
    }
}
