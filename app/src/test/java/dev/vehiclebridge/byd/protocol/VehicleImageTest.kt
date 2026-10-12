package dev.vehiclebridge.byd.protocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VehicleImageTest {
    @Test fun imageFieldIsPreservedWithoutTheShortTextTruncation() {
        val url="https://images.example.test/"+"a".repeat(180)+".png"
        val car=Vehicle.fromJson(JSONObject().put("vin","L0000000000000001").put("diFansVehicleImg",url))!!
        assertEquals(url,car.imageUrl)
        assertEquals("Vehicle([redacted])",car.toString())
    }
    @Test fun missingNullAndOversizedImageFieldsUsePlaceholder() {
        for(value in listOf(null,JSONObject.NULL,"null","x".repeat(2049))) {
            val car=Vehicle.fromJson(JSONObject().put("vin","L0000000000000001").put("diFansVehicleImg",value))!!
            assertEquals("",car.imageUrl)
        }
    }
}
