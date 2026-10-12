package dev.vehiclebridge.byd.bridge

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.TextView

/** Connection state stays separate from vehicle command results. */
internal fun TextView.showBridgeStatus(enabled: Boolean) {
    val state=if(enabled) BridgeStatus.connection else BridgeConnection.STOPPED
    val (label,foreground,background)=when(state) {
        BridgeConnection.CONNECTED -> Triple("● 后台联动已连接", "#166534", "#DCFCE7")
        BridgeConnection.CONNECTING -> Triple("● 后台联动连接中", "#92400E", "#FEF3C7")
        BridgeConnection.DISCONNECTED -> Triple("● 后台联动已断开 · 自动重连中", "#991B1B", "#FEE2E2")
        BridgeConnection.STOPPED -> Triple(if(enabled) "● 后台联动等待启动" else "● 后台联动已停止", "#475569", "#E2E8F0")
    }
    contentDescription=label.removePrefix("● ")+"，点击查看后台联动"
    text=when(state) {
        BridgeConnection.CONNECTED -> "● 联动已连接"
        BridgeConnection.CONNECTING -> "● 联动连接中"
        BridgeConnection.DISCONNECTED -> "● 联动重连中"
        BridgeConnection.STOPPED -> if(enabled) "● 联动待启动" else "● 联动已停止"
    }
    setTextColor(Color.parseColor(foreground));textSize=12f
    setTypeface(typeface,Typeface.BOLD)
    val scale=resources.displayMetrics.density
    val horizontal=(10*scale).toInt();val vertical=(6*scale).toInt();setPadding(horizontal,vertical,horizontal,vertical)
    this.background=GradientDrawable().apply {setColor(Color.parseColor(background));cornerRadius=12*scale}
}
