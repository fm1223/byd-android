package dev.vehiclebridge.byd.data
import dev.vehiclebridge.byd.protocol.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TelemetryTransportTest {
    private val config=AccountConfig("synthetic-account","synthetic-password")
    private val session=Session("user","super","sign","content",1L)
    private val car=Vehicle("L0000000000000001","Car","","",true)
    private fun response(data: JSONObject)=JSONObject().put("code","0").put("respondData",CnCrypto.encryptContent(data.toString(),CnCrypto.md5Upper(session.encryToken)))
    @Test fun telemetryUsesSharedIdentityAndNeverSendsPinWakeOrDoorCommands() {
        val calls=mutableListOf<String>()
        val api=BydApi {endpoint,request ->
            calls.add(endpoint)
            val outer=JSONObject(request)
            assertEquals(1,outer.getInt("identifierType"));assertEquals(car.vin,outer.getString("objective"))
            val inner=JSONObject(CnCrypto.decryptContent(outer.getString("encryData"),CnCrypto.md5Upper(session.encryToken)))
            assertEquals("0",inner.getString("energyType"));assertEquals("3",inner.getString("tboxVersion"))
            assertFalse(inner.has("commandPwd"));assertFalse(inner.has("commandType"))
            response(JSONObject().put("totalMileage",321))
        }
        assertTrue(api.telemetry(config,session,car).hasData)
        assertEquals(listOf("/vehicleInfo/vehicle/vehicleRealTimeRequest"),calls)
    }
    @Test fun rejectionDoesNotPollOrRetry() {
        var calls=0
        val api=BydApi {_,_ -> calls++;JSONObject().put("code","240")}
        assertEquals("240",assertThrows(ApiFailure::class.java) {api.telemetry(config,session,car)}.code)
        assertEquals(1,calls)
    }
    @Test fun pendingResponseMakesOnlyOneResultQuery() {
        val calls=mutableListOf<String>()
        val api=BydApi {endpoint,_ -> calls.add(endpoint);response(JSONObject().put("requestSerial","synthetic-serial"))}
        assertFalse(api.telemetry(config,session,car).hasData)
        assertEquals(listOf("/vehicleInfo/vehicle/vehicleRealTimeRequest","/vehicleInfo/vehicle/vehicleRealTimeResult"),calls)
    }
}
