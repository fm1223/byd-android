package dev.vehiclebridge.byd.data

import dev.vehiclebridge.byd.protocol.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VehicleStatusPresentationTest {
    private val now=1_800_000_000_000L
    private fun body()=JSONObject().put("time",now).put("leftFrontDoorLock",2).put("rightFrontDoorLock",2).put("leftRearDoorLock",2).put("rightRearDoorLock",2).put("trunkLid",0).put("powerGear",1)
    @Test fun freshReportsChooseOppositeActionWithoutInferringEngineState() {
        val status=VehicleStatus(VehicleStateReport(body()))
        assertEquals(DoorAction.UNLOCK,status.doorAction(now));assertEquals(DoorAction.OPEN_TRUNK,status.trunkAction(now))
        assertTrue(status.summary(now).contains("电源：下电"));assertTrue(status.summary(now).contains("不能直接证明发动机"))
        val data=body();listOf("leftFrontDoorLock","rightFrontDoorLock","leftRearDoorLock","rightRearDoorLock").forEach {data.put(it,1)};data.put("trunkLid",1)
        val other=VehicleStatus(VehicleStateReport(data))
        assertEquals(DoorAction.LOCK,other.doorAction(now));assertEquals(DoorAction.CLOSE_TRUNK,other.trunkAction(now))
    }
    @Test fun acknowledgementDoesNotTurnPreCommandReportIntoNewState() {
        val status=VehicleStatus(VehicleStateReport(body()),notBefore=now+1)
        assertTrue(status.awaitingReport);assertNull(status.doorAction(now));assertNull(status.trunkAction(now))
        assertTrue(status.summary(now).contains("等待操作后的车辆上报"))
        val updated=VehicleStatus(VehicleStateReport(body().put("time",now+1000)),notBefore=now+1)
        assertFalse(updated.awaitingReport);assertEquals(DoorAction.UNLOCK,updated.doorAction(now+1000))
    }
    @Test fun staleFutureMissingAndMixedReportsRequireExplicitChoice() {
        val stale=VehicleStatus(VehicleStateReport(body().put("time",now-120001)))
        assertNull(stale.doorAction(now));assertNull(stale.trunkAction(now));assertTrue(stale.summary(now).contains("过期"))
        assertNull(VehicleStatus(VehicleStateReport(body().put("time",now+30001))).doorAction(now))
        assertNull(VehicleStatus(VehicleStateReport(body().put("leftFrontDoorLock",1))).doorAction(now))
        val partial=body();partial.remove("leftFrontDoorLock");assertNull(VehicleStatus(VehicleStateReport(partial)).doorAction(now))
        assertNull(VehicleStatus().doorAction(now));assertNull(VehicleStatus().trunkAction(now))
    }
}
