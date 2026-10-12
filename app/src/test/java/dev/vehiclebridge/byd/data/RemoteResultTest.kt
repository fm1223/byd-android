package dev.vehiclebridge.byd.data

import dev.vehiclebridge.byd.protocol.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteResultTest {
    private val config=AccountConfig("synthetic-account","synthetic-password")
    private val session=Session("user","super","sign","content",1L)
    private val vehicle=Vehicle("L0000000000000001","","","",true)
    private val protocol=CnProtocol(config)
    private fun envelope(data: String)=JSONObject().put("code",0).put("respondData",CnCrypto.encryptContent(data,CnCrypto.md5Upper(session.encryToken)))
    private fun inner(outer: String)=JSONObject(CnCrypto.decryptContent(JSONObject(outer).getString("encryData"),CnCrypto.md5Upper(session.encryToken)))
    @Test fun parsesNestedSerialAndBothDocumentedStatusFormats() {
        assertEquals("12345",protocol.controlReceipt(envelope("""{"code":"200","respondData":12345}"""),session).serial)
        for((payload,expected) in listOf("""{"res":1}""" to RemoteOutcome.PENDING,"""{"res":2}""" to RemoteOutcome.SUCCESS,"""{"res":3}""" to RemoteOutcome.FAILURE,"""{"controlState":0}""" to RemoteOutcome.PENDING,"""{"controlState":1}""" to RemoteOutcome.SUCCESS,"""{"data":{"controlState":2}}""" to RemoteOutcome.FAILURE)) {
            assertEquals(expected,protocol.controlReceipt(envelope(payload),session).outcome)
        }
        for(payload in listOf("{}","SUCCESS","""{"code":200}""","""{"res":0}""","""{"result":"SUCCESS"}""")) assertEquals(RemoteOutcome.UNKNOWN,protocol.controlReceipt(envelope(payload),session).outcome)
    }
    @Test fun resultQueryCarriesOriginalCommandForAllActionsAndIdentities() {
        for(action in DoorAction.entries) for(shared in listOf(false,true)) {
            val target=Vehicle(vehicle.vin,"","","",shared)
            val outer=protocol.controlResultRequest(session,target,"serial-1",action)
            assertEquals(if(shared) 1 else 0,JSONObject(outer).getInt("identifierType"))
            val data=inner(outer)
            assertEquals(action.command,data.getString("commandType"))
            assertEquals("serial-1",data.getString("requestSerial"))
            assertEquals(target.vin,data.getString("vin"))
            for(key in listOf("commandPwd","controlParamsMap","asyncControl")) assertFalse(data.has(key))
        }
    }
    @Test fun emptyAcknowledgementQueriesOriginalSerialWithoutResending() {
        val endpoints=mutableListOf<String>();var sent=""
        val api=BydApi(pause={}) {endpoint,outer ->
            endpoints.add(endpoint)
            assertEquals(1,JSONObject(outer).getInt("identifierType"))
            when {
                endpoint.endsWith("remoteControl") -> {sent=inner(outer).getString("requestSerial");JSONObject().put("code",0)}
                endpoint.endsWith("remoteControlResult") -> {assertEquals(sent,inner(outer).getString("requestSerial"));assertEquals("LOCKDOOR",inner(outer).getString("commandType"));envelope("""{"controlState":1}""")}
                else -> JSONObject().put("code",0)
            }
        }
        val result=api.executeControl(config,session,vehicle,"123456",DoorAction.LOCK) {}
        assertEquals(RemoteOutcome.SUCCESS,result.outcome)
        assertEquals(listOf("/control/rc/remoteAwakeRequest","/control/rc/remoteControl","/control/rc/remoteControlResult"),endpoints)
    }
    @Test fun serverSerialOverridesOriginalAndPendingTransitionsToSuccess() {
        var queries=0;var commands=0;val progress=mutableListOf<String>()
        val api=BydApi(pause={}) {endpoint,outer -> when {
            endpoint.endsWith("remoteControl") -> {commands++;envelope("""{"requestSerial":"server-serial"}""")}
            endpoint.endsWith("remoteControlResult") -> {assertEquals("server-serial",inner(outer).getString("requestSerial"));assertEquals("CLOSEAIR",inner(outer).getString("commandType"));queries++;envelope(if(queries==1) """{"res":1}""" else """{"res":2}""")}
            else -> JSONObject().put("code",0)
        }}
        assertEquals(RemoteOutcome.SUCCESS,api.executeControl(config,session,vehicle,"123456",DoorAction.SHUTDOWN,progress::add).outcome)
        assertEquals(1,commands);assertEquals(2,queries);assertTrue(progress.any {it.contains("执行中")})
    }
    @Test fun mismatchedSerialCannotReportSuccess() {
        val api=BydApi(pause={}) {endpoint,_ -> when {
            endpoint.endsWith("remoteControl") -> envelope("""{"requestSerial":"expected"}""")
            endpoint.endsWith("remoteControlResult") -> envelope("""{"requestSerial":"other","controlState":1}""")
            else -> JSONObject().put("code",0)
        }}
        assertEquals(RemoteOutcome.UNKNOWN,api.executeControl(config,session,vehicle,"123456",DoorAction.LOCK) {}.outcome)
    }
    @Test fun queryRejectionDoesNotTurnAcceptedCommandIntoFailureOrResend() {
        var commands=0;var queries=0
        val api=BydApi(pause={}) {endpoint,_ -> when {
            endpoint.endsWith("remoteControl") -> {commands++;JSONObject().put("code",0)}
            endpoint.endsWith("remoteControlResult") -> {queries++;JSONObject().put("code",242)}
            else -> JSONObject().put("code",0)
        }}
        val result=api.executeControl(config,session,vehicle,"123456",DoorAction.LOCK) {}
        assertEquals(RemoteOutcome.UNKNOWN,result.outcome);assertTrue(result.detail.contains("242"))
        assertEquals(1,commands);assertEquals(1,queries)
    }
    @Test fun emptyResultsHaveBoundedPollingAndNeverMeanSuccess() {
        var commands=0;var queries=0
        val api=BydApi(pause={}) {endpoint,_ ->
            if(endpoint.endsWith("remoteControl")) commands++
            if(endpoint.endsWith("remoteControlResult")) queries++
            JSONObject().put("code",0)
        }
        assertEquals(RemoteOutcome.UNKNOWN,api.executeControl(config,session,vehicle,"123456",DoorAction.UNLOCK) {}.outcome)
        assertEquals(1,commands);assertEquals(5,queries)
    }
    @Test fun immediateTerminalFailureDoesNotPoll() {
        var queries=0
        val api=BydApi(pause={}) {endpoint,_ ->
            if(endpoint.endsWith("remoteControlResult")) queries++
            if(endpoint.endsWith("remoteControl")) envelope("""{"controlState":2}""") else JSONObject().put("code",0)
        }
        assertEquals(RemoteOutcome.FAILURE,api.executeControl(config,session,vehicle,"123456",DoorAction.LOCK) {}.outcome)
        assertEquals(0,queries)
    }
}
