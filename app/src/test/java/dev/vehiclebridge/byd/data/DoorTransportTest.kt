package dev.vehiclebridge.byd.data

import dev.vehiclebridge.byd.protocol.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DoorTransportTest {
    private val config=AccountConfig("synthetic-account","synthetic-password")
    private val session=Session("user","super","sign","content",1L)
    private val vehicle=Vehicle("L0000000000000001","","","",true)
    @Test fun shutdownUsesChinaControlEndpointAndSharedIdentity() {
        val endpoints=mutableListOf<String>()
        val api=BydApi {endpoint,outer ->
            endpoints.add(endpoint)
            val envelope=JSONObject(outer)
            assertEquals(1,envelope.getInt("identifierType"))
            if(endpoint.endsWith("remoteControl")) {
                val inner=JSONObject(CnCrypto.decryptContent(envelope.getString("encryData"),CnCrypto.md5Upper(session.encryToken)))
                assertEquals("CLOSEAIR",inner.getString("commandType"))
                assertEquals(CnCrypto.md5Upper("123456"),inner.getString("commandPwd"))
                assertEquals("{}",inner.getString("controlParamsMap"))
            }
            JSONObject().put("code","0")
        }
        api.sendDoor(config,session,vehicle,"123456",DoorAction.SHUTDOWN)
        assertEquals(listOf("/control/rc/remoteAwakeRequest","/control/rc/remoteControl"),endpoints)
    }
    @Test fun wakeRejectionIdentifiesStageAndNeverSendsShutdown() {
        val endpoints=mutableListOf<String>()
        val api=BydApi {endpoint,_ -> endpoints.add(endpoint);JSONObject().put("code","242")}
        val failure=assertThrows(ApiFailure::class.java) {api.sendDoor(config,session,vehicle,"123456",DoorAction.SHUTDOWN)}
        assertEquals("242",failure.code)
        assertTrue(failure.label.contains("车辆唤醒失败"))
        assertTrue(failure.label.contains("控制命令未发送"))
        assertEquals(listOf("/control/rc/remoteAwakeRequest"),endpoints)
    }
    @Test fun shutdownRejectionIdentifiesCommandAndNeverFallsBackOrRetries() {
        val endpoints=mutableListOf<String>()
        val api=BydApi {endpoint,_ ->
            endpoints.add(endpoint)
            JSONObject().put("code",if(endpoint.endsWith("remoteControl")) "242" else "0")
        }
        val failure=assertThrows(ApiFailure::class.java) {api.sendDoor(config,session,vehicle,"123456",DoorAction.SHUTDOWN)}
        assertEquals("242",failure.code)
        assertTrue(failure.label.contains("熄火请求被服务器拒绝"))
        assertEquals(listOf("/control/rc/remoteAwakeRequest","/control/rc/remoteControl"),endpoints)
    }
    @Test fun wakeFailureNeverDispatchesDoorCommand() {
        val endpoints=mutableListOf<String>()
        val api=BydApi {endpoint,_ -> endpoints.add(endpoint);JSONObject().put("code","240")}
        assertThrows(ApiFailure::class.java) {api.sendDoor(config,session,vehicle,"123456",DoorAction.UNLOCK)}
        assertEquals(listOf("/control/rc/remoteAwakeRequest"),endpoints)
    }
    @Test fun commandTimeoutIsUnknownAndNeverRetried() {
        val endpoints=mutableListOf<String>()
        val api=BydApi {endpoint,_ ->
            endpoints.add(endpoint)
            if(endpoint.endsWith("remoteControl")) throw ApiFailure(null,"网络请求失败")
            JSONObject().put("code","0")
        }
        val failure=assertThrows(ApiFailure::class.java) {api.sendDoor(config,session,vehicle,"123456",DoorAction.LOCK)}
        assertTrue(failure.label.contains("结果未知"));assertEquals(2,endpoints.size)
        assertEquals(1,endpoints.count {it.endsWith("remoteControl")})
    }
    @Test fun emptyCommandAcknowledgementIsAcceptedAndDoesNotQueryResults() {
        val endpoints=mutableListOf<String>()
        val api=BydApi {endpoint,outer ->
            endpoints.add(endpoint)
            assertEquals(1,JSONObject(outer).getInt("identifierType"))
            JSONObject().put("code","0")
        }
        api.sendDoor(config,session,vehicle,"123456",DoorAction.UNLOCK)
        assertEquals(listOf("/control/rc/remoteAwakeRequest","/control/rc/remoteControl"),endpoints)
    }
    @Test fun encryptedBusinessRejectionIsNotTreatedAsAcceptedRequest() {
        val protocol=CnProtocol(config)
        val rejected=JSONObject().put("code","0").put("respondData",CnCrypto.encryptContent("""{"code":"6024"}""",CnCrypto.md5Upper(session.encryToken)))
        assertEquals("6024",assertThrows(ApiFailure::class.java) {protocol.rcAcknowledgement(rejected,session)}.code)
        protocol.rcAcknowledgement(JSONObject().put("code","0").put("respondData",CnCrypto.encryptContent("SUCCESS",CnCrypto.md5Upper(session.encryToken))),session)
    }
}
