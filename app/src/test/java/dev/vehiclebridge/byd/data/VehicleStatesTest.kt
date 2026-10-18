package dev.vehiclebridge.byd.data

import dev.vehiclebridge.byd.protocol.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class VehicleStatesTest {
    private val config=AccountConfig("synthetic-account","synthetic-password")
    private val session=Session("synthetic-user","synthetic-super","synthetic-sign","synthetic-content",1L)
    private val vehicle=Vehicle("L0000000000000001","","","",true)
    private fun envelope(data: JSONObject)=JSONObject().put("code",0).put("respondData",CnCrypto.encryptContent(data.toString(),CnCrypto.md5Upper(session.encryToken)))
    private fun locked()=JSONObject().put("time",System.currentTimeMillis()).put("leftFrontDoorLock",2).put("rightFrontDoorLock",2).put("leftRearDoorLock",2).put("rightRearDoorLock",2)
    @Test fun freshDoorLocksDoNotShortCircuitMissingTrunkAndPowerFields() {
        val calls=mutableListOf<String>()
        val api=BydApi(pause={}) {path,outer ->
            calls.add(path);assertEquals(1,JSONObject(outer).getInt("identifierType"))
            val inner=JSONObject(CnCrypto.decryptContent(JSONObject(outer).getString("encryData"),CnCrypto.md5Upper(session.encryToken)))
            assertEquals(vehicle.vin,inner.getString("vin"));assertFalse(inner.has("commandPwd"));assertFalse(inner.has("commandType"))
            when {
                path.endsWith("Request") -> envelope(locked().put("requestSerial","one"))
                path.endsWith("Result") -> {assertEquals("one",inner.getString("requestSerial"));envelope(locked().put("trunkLid",1).put("powerGear",3))}
                else -> envelope(JSONObject().put("status",2))
            }
        }
        val result=api.fetchVehicleStatus(config,session,vehicle).describe()
        assertTrue(result.contains("最近上报：打开（1）"));assertTrue(result.contains("上电（3"))
        assertEquals(listOf("/vehicleInfo/vehicle/vehicleRealTimeRequest","/vehicleInfo/vehicle/vehicleRealTimeResult"),calls)
    }
    @Test fun rejectedStatusDoesNotStartHvacOrRetry() {
        var calls=0
        val api=BydApi(pause={}) {_,_ -> calls++;JSONObject().put("code",240).put("message","需要实名认证")}
        val result=api.fetchVehicleStatus(config,session,vehicle).describe()
        assertTrue(result.contains("需要实名认证"));assertTrue(result.contains("代码 240"));assertEquals(1,calls)
    }
    @Test fun completeOldReportReturnsImmediatelyWithoutHvacOrPolling() {
        var calls=0
        val api=BydApi(pause={fail("Complete report must not wait")}) {path,_ ->
            calls++;assertEquals("/vehicleInfo/vehicle/vehicleRealTimeRequest",path)
            envelope(locked().put("time",1).put("trunkLid",0).put("powerGear",1).put("requestSerial","old"))
        }
        val result=api.fetchVehicleStatus(config,session,vehicle)
        assertEquals(1,calls);assertEquals(0,result.report!!.trunk);assertEquals(1,result.report!!.power)
        assertFalse(result.report!!.locks.recent(System.currentTimeMillis()))
    }
    @Test fun expiredSessionIsExposedForBoundedReadOnlyRecovery() {
        val api=BydApi(pause={}) {_,_ -> JSONObject().put("code",1002)}
        assertTrue(assertThrows(ApiFailure::class.java) {api.fetchVehicleStatus(config,session,vehicle)}.sessionExpired)
    }
    @Test fun base64HvacAndIndependentMissingTimestampAreExplicit() {
        val raw=JSONObject().put("status",2).put("acSwitch",0)
        val text=VehicleStateReport.hvacDescription(JSONObject().put("outputBase64",Base64.getEncoder().encodeToString(raw.toString().toByteArray())))
        assertTrue(text.contains("关闭（status=2"));assertTrue(text.contains("未提供，不能判断数据新鲜度"))
    }
    @Test fun missingAndBooleanFieldsNeverBecomeClosedOrPoweredOff() {
        val text=VehicleStateReport(JSONObject().put("trunkLid",false).put("powerGear",false)).describe()
        assertTrue(text.contains("最近上报：未知"));assertFalse(text.contains("最近上报：关闭"));assertFalse(text.contains("电源档位：下电"))
        assertTrue(VehicleStateReport.hvacDescription(JSONObject().put("status",false)).contains("未知／未提供"))
    }
}
