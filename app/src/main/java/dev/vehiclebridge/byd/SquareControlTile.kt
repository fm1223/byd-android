package dev.vehiclebridge.byd
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/** Square tile sized from its column width. */
class SquareControlTile @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null): LinearLayout(context,attrs) {
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(widthMeasureSpec),View.MeasureSpec.EXACTLY))
    }
}
