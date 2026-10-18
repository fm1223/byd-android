package dev.vehiclebridge.byd.data

import dev.vehiclebridge.byd.protocol.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VehicleOnlineStateTest {
    private fun body()=JSONObject().put("time",1).put("leftFrontDoorLock",2).put("rightFrontDoorLock",2).put("leftRearDoorLock",2).put("rightRearDoorLock",2).put("trunkLid",0)
    @Test fun onlineUsesReportedStateEvenWithAnOldTimestamp() {
        val status=VehicleStatus(VehicleStateReport(body().put("onlineState",1)))
        assertEquals(DoorAction.UNLOCK,status.reportedDoorAction());assertEquals(DoorAction.OPEN_TRUNK,status.reportedTrunkAction())
    }
    @Test fun offlineNeverOffersAutomaticOppositeActionFromCachedFields() {
        val status=VehicleStatus(VehicleStateReport(body().put("onlineState",2)))
        assertEquals(2,status.report!!.onlineState);assertNull(status.reportedDoorAction());assertNull(status.reportedTrunkAction())
    }
    @Test fun unknownMissingAndBooleanOnlineValuesAreNotInferredFromTboxConnection() {
        for(value in listOf<Any>(0,-1,3,true,"bad")) {
            val report=VehicleStateReport(body().put("onlineState",value).put("connectState",1))
            assertNull(report.onlineState);assertEquals(1,report.connectState)
            assertTrue(VehicleStatus(report).canShowReportedState)
            assertEquals(DoorAction.UNLOCK,VehicleStatus(report).reportedDoorAction())
        }
        assertNull(VehicleStateReport(body()).onlineState)
    }
    @Test fun missingOnlineFieldDoesNotHideKnownVehicleStates() {
        val status=VehicleStatus(VehicleStateReport(body()))
        assertTrue(status.canShowReportedState);assertFalse(status.explicitlyOffline)
        assertEquals(DoorAction.UNLOCK,status.reportedDoorAction());assertEquals(DoorAction.OPEN_TRUNK,status.reportedTrunkAction())
        assertFalse(VehicleStatus().canShowReportedState)
        assertFalse(VehicleStatus(VehicleStateReport(body().put("onlineState",2))).canShowReportedState)
    }
    @Test fun nestedVehicleInfoAndStringValuesAreSupported() {
        val report=VehicleStateReport(JSONObject().put("vehicleInfo",body().put("onlineState","1").put("connectState","0")))
        assertEquals(1,report.onlineState);assertEquals(0,report.connectState)
    }
}
