package dev.vehiclebridge.byd.bridge

import dev.vehiclebridge.byd.protocol.DoorAction
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Drop unsafe deliveries instead of queuing commands to run later. */
class CommandGate(private val topic: String, private val clock: ()->Long = System::nanoTime) {
    private var ready=false
    private var busy=false
    private var lastAction: DoorAction?=null
    private var lastTime=0L
    @Synchronized fun ready(value: Boolean) {ready=value}
    @Synchronized fun release() {busy=false}
    @Synchronized fun accept(actualTopic: String, payload: ByteArray, retained: Boolean, duplicate: Boolean): DoorAction? {
        if(!ready || busy || retained || duplicate || actualTopic!=topic || payload.size !in 2..3) return null
        val text=try {Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload)).toString()} catch(_:Exception) {return null}
        val action=when(text) {"on" -> DoorAction.UNLOCK;"off" -> DoorAction.LOCK;else -> return null}
        val now=clock()
        if(action==lastAction && now-lastTime<3_000_000_000L) return null
        lastAction=action;lastTime=now;busy=true
        return action
    }
}
