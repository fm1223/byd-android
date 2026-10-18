package dev.vehiclebridge.byd.bridge

import dev.vehiclebridge.byd.protocol.DoorAction
import org.junit.Assert.*
import org.junit.Test

class CommandSlotTest {
    @Test fun successiveDeliveriesReleaseGlobalAndTopicGatesAcrossDevices() {
        val slot=CommandSlot()
        val door=CommandGate("door001");door.ready(true)
        val trunk=CommandGate("trunk001",function=BemfaFunction.TRUNK);trunk.ready(true)
        val first=slot.accept(door,"door001","on".toByteArray(),false,false)!!
        assertEquals(DoorAction.UNLOCK,first.action)
        assertNull(slot.accept(trunk,"trunk001","on".toByteArray(),false,false))
        first.finish()
        val second=slot.accept(door,"door001","off".toByteArray(),false,false)!!
        assertEquals(DoorAction.LOCK,second.action)
        first.finish() // A late duplicate completion must not release a newer delivery.
        assertNull(slot.accept(trunk,"trunk001","on".toByteArray(),false,false))
        second.finish()
        val third=slot.accept(trunk,"trunk001","on".toByteArray(),false,false)!!
        assertEquals(DoorAction.OPEN_TRUNK,third.action);third.finish()
        val fourth=slot.accept(trunk,"trunk001","off".toByteArray(),false,false)!!
        assertEquals(DoorAction.CLOSE_TRUNK,fourth.action);fourth.finish()
    }
    @Test fun invalidAndRetainedMessagesDoNotBlockNextFreshCommand() {
        val slot=CommandSlot();val gate=CommandGate("door001");gate.ready(true)
        assertNull(slot.accept(gate,"door001","on".toByteArray(),true,false))
        assertNull(slot.accept(gate,"door001","invalid".toByteArray(),false,false))
        val valid=slot.accept(gate,"door001","on".toByteArray(),false,false)!!
        assertEquals(DoorAction.UNLOCK,valid.action);valid.finish()
        assertNotNull(slot.accept(gate,"door001","off".toByteArray(),false,false))
    }
}
