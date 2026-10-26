package dev.vehiclebridge.byd.bridge

import dev.vehiclebridge.byd.protocol.DoorAction
import org.junit.Assert.*
import org.junit.Test

class ClimateCommandGateTest {
    private fun accept(text: String): DoorAction? {
        val gate=CommandGate("ac005",function=BemfaFunction.CLIMATE);gate.ready(true)
        return gate.accept("ac005",text.toByteArray(),false,false)
    }
    @Test fun simpleAndStructuredMessagesUseOnlyTheSwitch() {
        for(text in listOf("on","on#1","on#3#20","on##23","on#2#17#0#0#1","on#7#31#5#1#0","on#####")) assertEquals(text,DoorAction.CLIMATE_ON,accept(text))
        for(text in listOf("off","off#0","off#3#20","off#####","off#0#23#0#0#0")) assertEquals(text,DoorAction.CLIMATE_OFF,accept(text))
    }
    @Test fun malformedAndOutOfRangeMessagesNeverBecomeCommands() {
        for(text in listOf("ON","on ","on#bad","on#8","on#1#15","on#1#16","on#1#32","on#1#33","on#1#23#6","on#1#23#1#2","on#1#23#1#0#2","on#1#23#1#0#0#1","off#garbage","on#1#23.5","on\u0000")) assertNull(text,accept(text))
        assertNull(accept("on#"+"1".repeat(200)))
    }
    @Test fun formattedMessagesRemainExclusiveToClimateTopics() {
        for(function in listOf(BemfaFunction.DOOR,BemfaFunction.TRUNK)) {
            val gate=CommandGate("other001",function=function);gate.ready(true)
            assertNull(gate.accept("other001","on#3#20".toByteArray(),false,false))
        }
        val gate=CommandGate("ac005",function=BemfaFunction.CLIMATE);gate.ready(true)
        assertNull(gate.accept("ac005","on#3#20".toByteArray(),true,false))
        assertNull(gate.accept("ac005","off#0".toByteArray(),false,true))
        assertNull(gate.accept("other","on".toByteArray(),false,false))
        assertNull(gate.accept("ac005",byteArrayOf(-1,-1),false,false))
        assertEquals(DoorAction.CLIMATE_ON,gate.accept("ac005","on".toByteArray(),false,false))
        gate.release()
        assertEquals(23,gate.acceptCommand("ac005","on#2#23".toByteArray(),false,false)?.temperatureCelsius)
        gate.release()
        assertEquals(DoorAction.CLIMATE_OFF,gate.accept("ac005","off#0".toByteArray(),false,false))
    }
 @Test fun temperatureIsForwardedOnlyForOnAndDuplicateIncludesTemperature() {
  val gate=CommandGate("ac005",function=BemfaFunction.CLIMATE);gate.ready(true)
  assertEquals(20,gate.acceptCommand("ac005","on#3#20".toByteArray(),false,false)?.temperatureCelsius)
  gate.release();assertNull(gate.acceptCommand("ac005","on#3#20".toByteArray(),false,false))
  assertEquals(23,gate.acceptCommand("ac005","on##23".toByteArray(),false,false)?.temperatureCelsius)
  gate.release();assertNull(gate.acceptCommand("ac005","off#3#20".toByteArray(),false,false)?.temperatureCelsius)
 }
}
