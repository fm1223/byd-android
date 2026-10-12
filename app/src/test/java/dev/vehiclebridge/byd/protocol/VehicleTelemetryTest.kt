package dev.vehiclebridge.byd.protocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VehicleTelemetryTest {
    @Test fun readingsPreserveZeroBatteryAndUseDeclaredPressureUnits() {
        val data=VehicleTelemetry.fromJson(JSONObject().put("totalMileage",12345.6).put("elecPercent",0)
            .put("leftFrontTirepressure",2.5).put("tirePressUnit",1).put("time",1700000000))
        assertEquals("12345.6 km",data.rows.toMap()["总里程"])
        assertEquals("0 %",data.rows.toMap()["电量"])
        assertEquals("2.5 bar",data.rows.toMap()["左前胎压"])
        assertEquals(1700000000000L,data.reportedAt)
    }
    @Test fun sentinelsInvalidValuesAndZeroPressureRemainUnavailable() {
        val data=VehicleTelemetry.fromJson(JSONObject().put("totalMileage",-1).put("elecPercent",101)
            .put("leftFrontTirepressure",0).put("nearestEnergyConsumption","--").put("recent50kmEnergy","-1"))
        assertFalse(data.hasData)
        assertFalse(VehicleTelemetry.fromJson(JSONObject().put("totalMileage",true)).hasData)
    }
    @Test fun energyAndAlternativeMileageNeverInventUnits() {
        val data=VehicleTelemetry.fromJson(JSONObject().put("totalMileageV2",100).put("nearestEnergyConsumption",15.6)
            .put("nearestEnergyConsumptionUnit","kWh/100km").put("recent50kmEnergy",18))
        assertEquals("100（单位未提供）",data.rows.toMap()["总里程"])
        assertEquals("15.6 kWh/100km",data.rows.toMap()["最近能耗"])
        assertEquals("18（单位未提供）",data.rows.toMap()["近 50 km 能耗"])
    }
    @Test fun listMileageIsAvailableWithoutAnExtraNetworkRequest() {
        val vehicle=Vehicle.fromJson(JSONObject().put("vin","L0000000000000001").put("totalMileage",123))!!
        assertEquals("123 km",vehicle.summary.rows.toMap()["总里程"])
    }
}
