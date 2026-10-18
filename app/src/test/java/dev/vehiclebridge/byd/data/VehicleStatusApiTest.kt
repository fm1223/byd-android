package dev.vehiclebridge.byd.data

import dev.vehiclebridge.byd.protocol.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VehicleStatusApiTest {
    private val config=AccountConfig("synthetic-account","synthetic-password")
    private val session=Session("synthetic-user","synthetic-super","synthetic-sign","synthetic-content",1L)
    private val vehicle=Vehicle("L0000000000000001","","","",true)
    private fun inner(text: String)=JSONObject(CnCrypto.decryptContent(JSONObject(text).getString("encryData"),CnCrypto.md5Upper(session.encryToken)))
    private fun envelope(data: JSONObject)=JSONObject().put("code",0).put("respondData",CnCrypto.encryptContent(data.toString(),CnCrypto.md5Upper(session.encryToken)))
    private fun locked(time: Long)=JSONObject().put("time",time).put("leftFrontDoorLock",2).put("rightFrontDoorLock",2).put("leftRearDoorLock",2).put("rightRearDoorLock",2)
    @Test fun missingZeroOrPartialLocksNeverMeanAllLocked() {
        assertFalse(VehicleLockReport(JSONObject()).describe().contains("全部锁定"))
        val data=locked(System.currentTimeMillis()).put("rightRearDoorLock",0)
        assertFalse(VehicleLockReport(data).completeAndRecent(System.currentTimeMillis()))
        assertFalse(VehicleLockReport(data).describe().contains("全部锁定"))
        data.put("leftFrontDoorLock",1);assertTrue(VehicleLockReport(data).describe().contains("检测到未锁车门"))
    }
    @Test fun staleOrMissingTimestampIsExplicit() {
        assertTrue(VehicleLockReport(locked(1)).describe().contains("不能确认当前状态"))
        val data=locked(System.currentTimeMillis());data.remove("time")
        assertTrue(VehicleLockReport(data).describe().contains("上报时间缺失"))
    }
    @Test fun sharedIdentityAndOriginalSerialUsedForReadOnlyQueriesEvenWhenTireDataExists() {
        val paths=mutableListOf<String>()
        val api=BydApi(pause={}) {path,outer ->
            paths.add(path);assertEquals(1,JSONObject(outer).getInt("identifierType"))
            assertEquals(vehicle.vin,inner(outer).getString("vin"));assertFalse(inner(outer).has("commandPwd"))
            if(path.endsWith("getStatusNow")) envelope(JSONObject())
            else if(path.endsWith("Request")) envelope(JSONObject().put("requestSerial","serial1").put("totalMileage",100).put("leftFrontTirepressure",2.5))
            else {assertEquals("serial1",inner(outer).getString("requestSerial"));envelope(locked(System.currentTimeMillis()).put("trunkLid",0).put("powerGear",1))}
        }
        assertTrue(api.fetchVehicleStatus(config,session,vehicle).describe().contains("全部锁定"))
        assertEquals(listOf("/vehicleInfo/vehicle/vehicleRealTimeRequest","/vehicleInfo/vehicle/vehicleRealTimeResult"),paths)
    }
    @Test fun realNameRejectionPreservedAndNoPollingOrControlIsSent() {
        var count=0
        val api=BydApi(pause={}) {_,outer -> count++;assertEquals(1,JSONObject(outer).getInt("identifierType"));JSONObject().put("code",240).put("message","被授权账号需要实名认证 synthetic-account synthetic-content")}
        val text=api.fetchVehicleStatus(config,session,vehicle).describe()
        assertTrue(text.contains("240"));assertTrue(text.contains("需要实名认证"))
        assertFalse(text.contains(config.username));assertFalse(text.contains(session.encryToken));assertEquals(1,count)
    }
    @Test fun emptyReportsStopAfterThreeResultQueries() {
        var count=0
        val api=BydApi(pause={}) {_,_ -> count++;envelope(JSONObject().put("requestSerial","serial1"))}
        assertTrue(api.fetchVehicleStatus(config,session,vehicle).describe().contains("未知"));assertEquals(4,count)
    }
}
