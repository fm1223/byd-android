package dev.vehiclebridge.byd.data

import dev.vehiclebridge.byd.protocol.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClimateControlTest {
    private val config=AccountConfig("synthetic-account","synthetic-password")
    private val session=Session("synthetic-user","synthetic-super","synthetic-sign","synthetic-content",1L)
    private fun inner(outer: String)=JSONObject(CnCrypto.decryptContent(JSONObject(outer).getString("encryData"),CnCrypto.md5Upper(session.encryToken)))
    @Test fun climateUsesChinaEnvelopeOneCommandAndMatchingResultQueryForBothIdentities() {
        for(action in listOf(DoorAction.CLIMATE_ON,DoorAction.CLIMATE_OFF)) for(shared in listOf(false,true)) {
            val vehicle=Vehicle("L0000000000000001","","","",shared)
            val paths=mutableListOf<String>();var serial=""
            val api=BydApi(pause={}) {path,outer ->
                paths.add(path);assertEquals(if(shared) 1 else 0,JSONObject(outer).getInt("identifierType"))
                val data=inner(outer)
                when {
                    path.endsWith("remoteControl") -> {
                        assertEquals(action.command,data.getString("commandType"));assertEquals(CnCrypto.md5Upper("123456"),data.getString("commandPwd"))
                        val params=JSONObject(data.getString("controlParamsMap"))
                        if(action==DoorAction.CLIMATE_ON) {
                            assertEquals(4,params.length());assertEquals(9,params.getInt("mainSettingTemp"));assertEquals(9,params.getInt("copilotSettingTemp"))
                            assertFalse(params.has("remoteMode"));assertFalse(params.has("windLevel"));assertEquals(1,params.getInt("timeSpan"));assertEquals(2,params.getInt("cycleMode"))
                        } else assertEquals(0,params.length())
                        serial=data.getString("requestSerial");JSONObject().put("code",0)
                    }
                    path.endsWith("remoteControlResult") -> {
                        assertEquals(action.command,data.getString("commandType"));assertEquals(serial,data.getString("requestSerial"))
                        JSONObject().put("code",0).put("respondData",CnCrypto.encryptContent(JSONObject().put("controlState",1).toString(),CnCrypto.md5Upper(session.encryToken)))
                    }
                    else -> JSONObject().put("code",0)
                }
            }
            val result=api.executeControl(config,session,vehicle,"123456",action) {}
            assertEquals(RemoteOutcome.SUCCESS,result.outcome)
            assertTrue(result.describe(action).startsWith(if(action==DoorAction.CLIMATE_ON) "开启空调：" else "关闭空调："))
            assertEquals(listOf("/control/rc/remoteAwakeRequest","/control/rc/remoteControl","/control/rc/remoteControlResult"),paths)
        }
    }
    @Test fun rejectedClimateCommandIsNeverRetried() {
        var sends=0
        val api=BydApi(pause={}) {path,_ -> if(path.endsWith("remoteControl")) {sends++;JSONObject().put("code",242)} else JSONObject().put("code",0)}
        assertThrows(ApiFailure::class.java) {api.executeControl(config,session,Vehicle("L0000000000000001","","","",true),"123456",DoorAction.CLIMATE_ON) {}}
        assertEquals(1,sends)
    }

    @Test fun selectedTemperatureIsEncodedForBothZonesAndSharesResultQueryWithoutResend() {
        for(celsius in listOf(17,23,25,26,31)) for(shared in listOf(false,true)) {
            val vehicle=Vehicle("L0000000000000001","","","",shared)
            var commands=0;var queries=0;var serial=""
            val api=BydApi(pause={}) {path,outer ->
                val data=inner(outer);assertEquals(if(shared) 1 else 0,JSONObject(outer).getInt("identifierType"))
                when {
                    path.endsWith("remoteControl") -> {
                        commands++;assertEquals("OPENAIR",data.getString("commandType"))
                        val params=JSONObject(data.getString("controlParamsMap"))
                        assertEquals(celsius-16,params.getInt("mainSettingTemp"));assertEquals(celsius-16,params.getInt("copilotSettingTemp"))
                        assertFalse(params.has("remoteMode"));assertFalse(params.has("windLevel"));assertEquals(1,params.getInt("timeSpan"));assertEquals(CnCrypto.md5Upper("123456"),data.getString("commandPwd"))
                        serial=data.getString("requestSerial");JSONObject().put("code",0)
                    }
                    path.endsWith("remoteControlResult") -> {
                        queries++;assertEquals("OPENAIR",data.getString("commandType"));assertEquals(serial,data.getString("requestSerial"))
                        assertFalse(data.has("controlParamsMap"))
                        JSONObject().put("code",0).put("respondData",CnCrypto.encryptContent(JSONObject().put("controlState",1).toString(),CnCrypto.md5Upper(session.encryToken)))
                    }
                    else -> JSONObject().put("code",0)
                }
            }
            assertEquals(RemoteOutcome.SUCCESS,api.executeClimateTemperature(config,session,vehicle,"123456",celsius) {}.outcome)
            assertEquals(1,commands);assertEquals(1,queries)
        }
        assertEquals(9,ClimateTemperature.encode(25));assertEquals(7,ClimateTemperature.encode(23));assertEquals(10,ClimateTemperature.encode(26))
    }
    @Test fun invalidTemperatureIsRejectedBeforeAnyNetworkOrWakeRequest() {
        val api=BydApi(pause={}) {_,_ -> fail("No network call allowed");JSONObject()}
        val vehicle=Vehicle("L0000000000000001","","","",true)
        for(value in listOf(0,16,32,100)) assertThrows(IllegalArgumentException::class.java) {api.executeClimateTemperature(config,session,vehicle,"123456",value) {}}
    }
}
