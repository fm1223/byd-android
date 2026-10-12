package dev.vehiclebridge.byd.bridge

import dev.vehiclebridge.byd.protocol.DoorAction
import org.junit.Assert.*
import org.junit.Test

class CommandGateTest {
    @Test fun rejectsHistoryDuplicatesWrongTopicAndUnknownPayloads() {
        val gate=CommandGate("car002");gate.ready(true)
        assertNull(gate.accept("car002","on".toByteArray(),true,false))
        assertNull(gate.accept("car002","off".toByteArray(),false,true))
        assertNull(gate.accept("other002","on".toByteArray(),false,false))
        for(text in listOf("ON","on ","toggle","on#1","{}")) assertNull(gate.accept("car002",text.toByteArray(),false,false))
        assertNull(gate.accept("car002",byteArrayOf(-1,-1),false,false))
    }
    @Test fun fixedMappingBusyDropAndShortDebounce() {
        var now=10_000_000_000L
        val gate=CommandGate("car002",{now});gate.ready(true)
        assertEquals(DoorAction.UNLOCK,gate.accept("car002","on".toByteArray(),false,false))
        assertNull(gate.accept("car002","off".toByteArray(),false,false))
        gate.release();now+=1_000_000_000L
        assertNull(gate.accept("car002","on".toByteArray(),false,false))
        assertEquals(DoorAction.LOCK,gate.accept("car002","off".toByteArray(),false,false))
        gate.release();gate.ready(false)
        assertNull(gate.accept("car002","on".toByteArray(),false,false))
        gate.ready(true);now+=4_000_000_000L
        assertEquals(DoorAction.UNLOCK,gate.accept("car002","on".toByteArray(),false,false))
    }
    @Test fun bindingRejectsWildcardAndNeverPrintsCredentials() {
        val key="00112233445566778899aabbccddeeff"
        assertThrows(IllegalArgumentException::class.java) {BemfaBinding(key,"car/#","L0000000000000001")}
        assertThrows(IllegalArgumentException::class.java) {BemfaBinding("invalid","car002","L0000000000000001")}
        assertEquals("BemfaBinding([redacted])",BemfaBinding(key,"car002","L0000000000000001").toString())
    }
}
