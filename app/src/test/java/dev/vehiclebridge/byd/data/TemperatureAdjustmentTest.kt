package dev.vehiclebridge.byd.data
import dev.vehiclebridge.byd.protocol.TemperatureAdjustment
import org.junit.Assert.*
import org.junit.Test
class TemperatureAdjustmentTest {
 @Test fun releaseCommitsOnlyOnce() {
  val gesture=TemperatureAdjustment();gesture.begin("vehicle-a",25)
  assertEquals(23,gesture.finish("vehicle-a",23,false))
  assertNull(gesture.finish("vehicle-a",24,false))
 }
 @Test fun cancelledUnchangedBusyAndVehicleChangeNeverSend() {
  val gesture=TemperatureAdjustment()
  gesture.begin("vehicle-a",25);gesture.cancel();assertNull(gesture.finish("vehicle-a",23,false))
  gesture.begin("vehicle-a",25);assertNull(gesture.finish("vehicle-a",25,false))
  gesture.begin("vehicle-a",25);assertNull(gesture.finish("vehicle-a",23,true))
  gesture.begin("vehicle-a",25);assertNull(gesture.finish("vehicle-b",23,false))
  gesture.begin("vehicle-a",25);assertNull(gesture.finish("vehicle-a",32,false))
 }
}
