package dev.vehiclebridge.byd.protocol

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VehicleControlTest {
    private val config=AccountConfig("synthetic-account","synthetic-password")
    private val protocol=CnProtocol(config,{1700000000000L},{"00112233445566778899AABBCCDDEEFF"})
    private val session=Session("synthetic-user","synthetic-super","synthetic-sign","synthetic-content",1L)
    @Test fun matchesNodePinWakeAndBothCommandsForOwnerAndSharedVehicles() {
        val fixtures=JSONObject(javaClass.getResourceAsStream("/control-node-fixtures.json")!!.bufferedReader().use {it.readText()})
        for(shared in listOf(false,true)) {
            val vehicle=Vehicle("L00000000000000001","","","",shared)
            val prefix=if(shared) "shared" else "owner"
            assertEquals(fixtures.getString(prefix+"Pin"),protocol.verifyPinRequest(session,vehicle,"123456"))
            assertEquals(fixtures.getString(prefix+"Wake"),protocol.wakeRequest(session,vehicle))
            assertEquals(fixtures.getString(prefix+"lock"),protocol.doorRequest(session,vehicle,"123456",DoorAction.LOCK))
            assertEquals(fixtures.getString(prefix+"unlock"),protocol.doorRequest(session,vehicle,"123456",DoorAction.UNLOCK))
        }
    }
    @Test fun detectsAuthorizationAndMasksVin() {
        for(value in listOf(JSONObject().put("empowerType",-1),JSONObject().put("empowerId",123))) {
            value.put("vin","L00000000000000001").put("autoAlias","测试车辆").put("modelName","合成车型")
            val vehicle=Vehicle.fromJson(value)!!
            assertTrue(vehicle.shared);assertFalse(vehicle.displayName.contains(vehicle.vin));assertEquals("合成车型",vehicle.model)
        }
        assertFalse(Vehicle.fromJson(JSONObject().put("vin","SYNTHETIC").put("empowerId",JSONObject.NULL))!!.shared)
    }
    @Test fun pinAcknowledgementDoesNotRequireDecryptablePayload() {
        protocol.controlAcknowledgement(JSONObject().put("code",0).put("respondData","opaque"))
        assertThrows(ApiFailure::class.java) {protocol.controlAcknowledgement(JSONObject().put("code",5005))}
        assertThrows(ApiFailure::class.java) {protocol.controlAcknowledgement(JSONObject().put("code",200))}
        protocol.controlAcknowledgement(JSONObject().put("code",200),true)
    }
}
