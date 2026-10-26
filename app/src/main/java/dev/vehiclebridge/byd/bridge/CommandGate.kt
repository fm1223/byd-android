package dev.vehiclebridge.byd.bridge

import dev.vehiclebridge.byd.protocol.DoorAction
import dev.vehiclebridge.byd.protocol.ClimateTemperature

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class BemfaCommand(val action: DoorAction,val temperatureCelsius: Int?=null)

/** Drop unsafe deliveries instead of queuing commands to run later. */
class CommandGate(private val topic: String, private val clock: ()->Long = System::nanoTime, private val function: BemfaFunction=BemfaFunction.DOOR) {
    private var ready=false
    private var busy=false
    private var lastCommand: BemfaCommand?=null
    private var lastTime=0L
    private fun climateSwitch(text: String): String? {
        val parts=text.split('#')
        if(parts.size !in 1..6 || parts[0] !in listOf("on","off")) return null
        val ranges=listOf(0..7,16..32,0..5,0..1,0..1)
        for(i in 1 until parts.size) {
            if(parts[i].isEmpty()) continue
            if(!Regex("[0-9]{1,2}").matches(parts[i]) || parts[i].toInt() !in ranges[i-1]) return null
        }
        // Mode, fan and swing remain unsupported; only the switch and temperature are forwarded.
        val temperature=parts.getOrNull(2)?.takeIf {it.isNotEmpty()}?.toIntOrNull()
        if(parts[0]=="on" && temperature!=null && temperature !in ClimateTemperature.MIN..ClimateTemperature.MAX) return null
        return parts[0]
    }
    @Synchronized fun ready(value: Boolean) {ready=value}
    @Synchronized fun release() {busy=false}
    fun accept(actualTopic: String,payload: ByteArray,retained: Boolean,duplicate: Boolean): DoorAction? = acceptCommand(actualTopic,payload,retained,duplicate)?.action
    @Synchronized fun acceptCommand(actualTopic: String, payload: ByteArray, retained: Boolean, duplicate: Boolean): BemfaCommand? {
        if(!ready || busy || retained || duplicate || actualTopic!=topic || payload.size !in (if(function==BemfaFunction.CLIMATE) 2..128 else 2..3)) return null
        val text=try {Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload)).toString()} catch(_:Exception) {return null}
        val switch=if(function==BemfaFunction.CLIMATE) climateSwitch(text) ?: return null else text
        val action=when(switch) {"on" -> function.onAction;"off" -> function.offAction;else -> return null}
        val temperature=if(function==BemfaFunction.CLIMATE && switch=="on") text.split('#').getOrNull(2)?.takeIf {it.isNotEmpty()}?.toIntOrNull() else null
        val command=BemfaCommand(action,temperature)
        val now=clock()
        if(command==lastCommand && now-lastTime<3_000_000_000L) return null
        lastCommand=command;lastTime=now;busy=true
        return command
    }
}
