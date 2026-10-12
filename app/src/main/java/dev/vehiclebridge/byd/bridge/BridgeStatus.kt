package dev.vehiclebridge.byd.bridge

import android.os.Handler
import android.os.Looper

enum class BridgeConnection { STOPPED, CONNECTING, CONNECTED, DISCONNECTED }

object BridgeStatus {
    private val main=Handler(Looper.getMainLooper())
    private val observers=mutableSetOf<(String)->Unit>()
    var connection=BridgeConnection.STOPPED;private set
    var text="后台联动未启动";private set
    fun observe(observer: (String)->Unit) {observers.add(observer);observer(text)}
    fun remove(observer: (String)->Unit) {observers.remove(observer)}
    fun update(value: String, state: BridgeConnection? = null) {main.post {
        if(state!=null) connection=state
        text=value;observers.toList().forEach {it(value)}
    }}
}
