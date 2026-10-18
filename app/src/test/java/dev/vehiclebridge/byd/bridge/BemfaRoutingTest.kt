package dev.vehiclebridge.byd.bridge

import dev.vehiclebridge.byd.protocol.DoorAction
import dev.vehiclebridge.byd.protocol.WireJson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BemfaRoutingTest {
    private val key="00112233445566778899aabbccddeeff"
    private val vin="L0000000000000001"
    @Test fun legacyRecordMigratesToDoorDeviceWithSameIdentityAndEnabledState() {
        val value=BemfaBindingCodec.decode(JSONObject().put("key",key).put("topic","car001").put("vin",vin).put("id","legacy").put("enabled",true))
        assertTrue(value.enabled);assertEquals(1,value.devices.size)
        assertEquals("legacy",value.devices.single().id);assertEquals(BemfaFunction.DOOR,value.devices.single().function)
    }
    @Test fun roundTripPreservesMultipleVehiclesFunctionsAndDisabledDevice() {
        val devices=listOf(BemfaDevice("door001",vin,id="door"),BemfaDevice("trunk001","L0000000000000002",BemfaFunction.TRUNK,"尾门",false,"trunk"))
        val value=BemfaBinding(key,"door001",vin,true,"config",devices)
        val decoded=BemfaBindingCodec.decode(JSONObject(WireJson.encode(BemfaBindingCodec.encode(value))))
        assertEquals("config",decoded.id);assertTrue(decoded.enabled)
        assertEquals(listOf("door001"),decoded.activeDevices.map {it.topic})
        assertEquals(BemfaFunction.TRUNK,decoded.devices.last().function);assertEquals("L0000000000000002",decoded.devices.last().vin)
        assertFalse(decoded.toString().contains(key));assertFalse(decoded.devices.last().toString().contains(vin))
    }
    @Test fun duplicateTopicOrDeviceIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {BemfaBinding(key,"same",vin,devices=listOf(BemfaDevice("same",vin),BemfaDevice("same",vin,BemfaFunction.TRUNK)))}
        assertThrows(IllegalArgumentException::class.java) {BemfaBinding(key,"one",vin,devices=listOf(BemfaDevice("one",vin,id="same"),BemfaDevice("two",vin,id="same")))}
    }
    @Test fun trunkGateMapsOnOffAndDropsWrongTopicRetainedAndDuplicateMessages() {
        val gate=CommandGate("trunk001",function=BemfaFunction.TRUNK);gate.ready(true)
        assertNull(gate.accept("door001","on".toByteArray(),false,false))
        assertNull(gate.accept("trunk001","on".toByteArray(),true,false))
        assertNull(gate.accept("trunk001","on".toByteArray(),false,true))
        assertEquals(DoorAction.OPEN_TRUNK,gate.accept("trunk001","on".toByteArray(),false,false))
        assertNull(gate.accept("trunk001","off".toByteArray(),false,false));gate.release()
        assertEquals(DoorAction.CLOSE_TRUNK,gate.accept("trunk001","off".toByteArray(),false,false))
    }
}
