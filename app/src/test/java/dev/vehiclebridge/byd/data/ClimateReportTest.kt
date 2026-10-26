package dev.vehiclebridge.byd.data

import dev.vehiclebridge.byd.protocol.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ClimateReportTest {
    @Test fun reportedStateAndTargetTemperatureDecodeIndependently() {
        val report=ClimateReport.parse(JSONObject().put("statusNow",JSONObject().put("status",1).put("mainSettingTemp",9)))
        assertEquals(true,report.running);assertEquals(25,report.targetCelsius)
        val precise=ClimateReport.parse(JSONObject().put("status",2).put("mainSettingTempNew",23).put("mainSettingTemp",9))
        assertEquals(false,precise.running);assertEquals(23,precise.targetCelsius)
    }
    @Test fun base64ReportAndMissingStateNeverInferSwitchFromPowerOrAcSwitch() {
        val payload=JSONObject().put("status",1).put("mainSettingTemp",7)
        val encoded=Base64.getEncoder().encodeToString(payload.toString().toByteArray())
        val parsed=ClimateReport.parse(JSONObject().put("outputBase64",encoded))
        assertEquals(true,parsed.running);assertEquals(23,parsed.targetCelsius)
        val unknown=ClimateReport.parse(JSONObject().put("acSwitch",0).put("powerGear",1))
        assertNull(unknown.running);assertNull(unknown.targetCelsius)
        val unavailable=ClimateReport.parse(JSONObject().put("status",0).put("mainSettingTemp",0).put("acSwitch",0))
        assertNull(unavailable.running);assertNull(unavailable.targetCelsius)
        assertEquals(25,VehicleStateReport(JSONObject().put("mainSettingTemp",9)).climateTargetCelsius)
        assertNull(ClimateReport.parse(JSONObject().put("status",false).put("mainSettingTemp",true)).running)
        assertThrows(ApiFailure::class.java) {ClimateReport.parse(JSONObject().put("outputBase64","invalid"))}
    }
    @Test fun chinaHvacReadUsesSharedIdentityWithoutPinOrControl() {
        val config=AccountConfig("synthetic-account","synthetic-password")
        val session=Session("synthetic-user","synthetic-super","synthetic-sign","synthetic-content",1L)
        val api=BydApi(pause={fail("No polling")}) {path,outer ->
            assertEquals("/control/rc/getStatusNow",path)
            val envelope=JSONObject(outer);assertEquals(1,envelope.getInt("identifierType"))
            val inner=JSONObject(CnCrypto.decryptContent(envelope.getString("encryData"),CnCrypto.md5Upper(session.encryToken)))
            assertFalse(inner.has("commandPwd"));assertFalse(inner.has("commandType"))
            JSONObject().put("code",0).put("respondData",CnCrypto.encryptContent(JSONObject().put("status",1).put("mainSettingTemp",9).toString(),CnCrypto.md5Upper(session.encryToken)))
        }
        val report=api.fetchClimateStatus(config,session,Vehicle("L0000000000000001","","","",true))
        assertEquals(true,report.running);assertEquals(25,report.targetCelsius)
    }
    @Test fun incompleteReadGetsOneReadOnlyFollowupAndPreservesKnownFields() {
        val config=AccountConfig("synthetic-account","synthetic-password")
        val session=Session("synthetic-user","synthetic-super","synthetic-sign","synthetic-content",1L)
        var reads=0;var pauses=0
        val api=BydApi(pause={assertEquals(1000L,it);pauses++}) {path,_ ->
            assertEquals("/control/rc/getStatusNow",path);reads++
            val body=if(reads==1) JSONObject().put("status",1) else JSONObject().put("mainSettingTemp",9)
            JSONObject().put("code",0).put("respondData",CnCrypto.encryptContent(body.toString(),CnCrypto.md5Upper(session.encryToken)))
        }
        val report=api.fetchClimateStatus(config,session,Vehicle("L0000000000000001","","","",true))
        assertEquals(2,reads);assertEquals(1,pauses)
        assertEquals(true,report.running);assertEquals(25,report.targetCelsius)
    }
}
