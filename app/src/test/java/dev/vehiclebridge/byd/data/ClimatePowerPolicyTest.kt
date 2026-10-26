package dev.vehiclebridge.byd.data
import dev.vehiclebridge.byd.protocol.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class ClimatePowerPolicyTest {
 private fun status(power: Int,online: Int=1)=VehicleStatus(VehicleStateReport(JSONObject().put("powerGear",power).put("onlineState",online)))
 @Test fun powerOffOverridesCachedOnAndSkipsHvacRead() {
  val off=status(1)
  assertFalse(off.canReadClimate());assertEquals(false,off.effectiveClimate(ClimateReport(true,25)).running)
 }
 @Test fun powerOnUsesIndependentReportWithoutAssumingOn() {
  val on=status(3)
  assertTrue(on.canReadClimate());assertNull(on.effectiveClimate(null).running)
  assertEquals(true,on.effectiveClimate(ClimateReport(true,25)).running)
  assertEquals(false,on.effectiveClimate(ClimateReport(false)).running)
 }
 @Test fun missingUnknownAndOfflinePowerNeverUseCachedHvac() {
  for(value in listOf(null,status(0),status(1,2),status(3,2))) {
   assertFalse(value.canReadClimate());assertNull(value.effectiveClimate(ClimateReport(true,25)).running)
  }
 }
}
