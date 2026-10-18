package dev.vehiclebridge.byd.bridge

import dev.vehiclebridge.byd.protocol.DoorAction
import java.util.concurrent.atomic.AtomicBoolean

/** Owns both global and topic gates for exactly one accepted delivery. */
internal class CommandSlot {
    private val busy=AtomicBoolean(false)
    fun accept(gate: CommandGate,topic: String,payload: ByteArray,retained: Boolean,duplicate: Boolean): Delivery? {
        if(!busy.compareAndSet(false,true)) return null
        val action=gate.accept(topic,payload,retained,duplicate)
        if(action==null) {busy.set(false);return null}
        return Delivery(action) {gate.release();busy.set(false)}
    }
    class Delivery(val action: DoorAction,private val release: ()->Unit) {
        private val finished=AtomicBoolean(false)
        fun finish() {if(finished.compareAndSet(false,true)) release()}
    }
}
