package dev.vehiclebridge.byd
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/** Equal-width control columns always measure as square touch targets. */
class SquareControlTile @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null): LinearLayout(context,attrs) {
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val side=View.MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(side,View.MeasureSpec.EXACTLY))
    }
}
