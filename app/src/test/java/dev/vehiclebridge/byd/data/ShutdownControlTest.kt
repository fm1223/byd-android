package dev.vehiclebridge.byd.data

import dev.vehiclebridge.byd.protocol.*
import dev.vehiclebridge.byd.bridge.BemfaBinding
import org.junit.Assert.*
import org.junit.Test

class ShutdownControlTest {
    private val vin="L0000000000000001"
    private val other="L0000000000000002"
    private val config=AccountConfig("synthetic-account","synthetic-password")
    private val session=Session("user",null,"sign","content",1L)
    private class Store(var value: SavedAccount): SessionStorage {
        override fun read()=value
        override fun write(value: SavedAccount) {this.value=value}
        override fun clear() {}
    }
    private class Api(val vehicles: List<Vehicle>): AccountApi {
        val commands=mutableListOf<DoorAction>()
        var fail: DoorAction?=null
        var outcome=RemoteOutcome.ACCEPTED
        override fun executeControl(config: AccountConfig,session: Session,vehicle: Vehicle,pin: String,action: DoorAction,progress: (String)->Unit): ControlReceipt {
            sendDoor(config,session,vehicle,pin,action);return ControlReceipt(outcome)
        }
        override fun login(config: AccountConfig)=error("Unexpected login")
        override fun vehicleCount(config: AccountConfig,session: Session)=vehicles.size
        override fun vehicles(config: AccountConfig,session: Session)=vehicles
        override fun verifyPin(config: AccountConfig,session: Session,vehicle: Vehicle,pin: String) {}
        override fun sendDoor(config: AccountConfig,session: Session,vehicle: Vehicle,pin: String,action: DoorAction) {
            commands.add(action)
            if(action==fail) throw ApiFailure("240","服务器拒绝请求")
        }
    }
    private fun fixture(enabled: Boolean=false): Triple<SessionRepository,Api,Store> {
        val api=Api(listOf(Vehicle(vin,"","","",true),Vehicle(other,"","","",false)))
        val store=Store(SavedAccount(config,session,vin,mapOf(vin to "123456",other to "123456"),shutdownOnLock=if(enabled) setOf(vin) else emptySet()))
        val repo=SessionRepository(api,store);repo.restore()
        return Triple(repo,api,store)
    }
    @Test fun confirmedShutdownFailureSkipsLockAndReportsBothSteps() {
        val (repo,api,_)=fixture(true);api.outcome=RemoteOutcome.FAILURE
        val result=repo.sendDoor(vin,DoorAction.LOCK)
        assertEquals(listOf(DoorAction.SHUTDOWN),api.commands)
        assertTrue(result.controlMessage!!.contains("熄火：服务器报告执行失败"))
        assertTrue(result.controlMessage!!.contains("锁车：未发送"))
    }
    @Test fun recentResultsBelongToVehicleAndSurviveTabLikeSnapshots() {
        val (repo,api,_)=fixture();api.outcome=RemoteOutcome.SUCCESS
        repo.sendDoor(vin,DoorAction.LOCK)
        assertTrue(repo.snapshot()!!.selectedControlMessage!!.contains("执行成功"))
        assertNull(repo.select(other).selectedControlMessage)
        assertTrue(repo.select(vin).selectedControlMessage!!.contains("锁车"))
    }
    @Test fun defaultsToOnlyLockAndAllowsIndependentShutdown() {
        val (repo,api,_)=fixture()
        repo.sendDoor(vin,DoorAction.LOCK);repo.sendDoor(vin,DoorAction.SHUTDOWN)
        assertEquals(listOf(DoorAction.LOCK,DoorAction.SHUTDOWN),api.commands)
        assertEquals("CLOSEAIR",DoorAction.SHUTDOWN.command)
    }
    @Test fun combinedLockSendsInOrderAndReportsRequests() {
        val (repo,api,_)=fixture(true)
        val result=repo.sendDoor(vin,DoorAction.LOCK)
        assertEquals(listOf(DoorAction.SHUTDOWN,DoorAction.LOCK),api.commands)
        assertTrue(result.controlMessage!!.contains("锁车：请求已发送") && result.controlMessage!!.contains("熄火：请求已发送"))
    }
    @Test fun unknownShutdownResultStillLocksWithoutRetryAndKeepsProgressInOrder() {
        val (repo,api,_)=fixture(true);api.outcome=RemoteOutcome.UNKNOWN
        val progress=mutableListOf<String>()
        val result=repo.sendDoor(vin,DoorAction.LOCK,progress=progress::add)
        assertEquals(listOf(DoorAction.SHUTDOWN,DoorAction.LOCK),api.commands)
        assertEquals("熄火：请求已发送，结果未知\n锁车：请求已发送，结果未知",result.controlMessage)
        assertTrue(progress.any {it.startsWith("熄火：") && it.endsWith("锁车：正在发送请求")})
    }
    @Test fun failedShutdownNeverSendsLock() {
        val (repo,api,_)=fixture(true);api.fail=DoorAction.SHUTDOWN
        assertThrows(ApiFailure::class.java) {repo.sendDoor(vin,DoorAction.LOCK)}
        assertEquals(listOf(DoorAction.SHUTDOWN),api.commands)
    }
    @Test fun failedLockPreservesShutdownResultAndNeverRetries() {
        val (repo,api,_)=fixture(true);api.fail=DoorAction.LOCK
        val error=assertThrows(ApiFailure::class.java) {repo.sendDoor(vin,DoorAction.LOCK)}
        assertTrue(error.label.contains("熄火：请求已发送"));assertEquals("240",error.code)
        assertEquals(listOf(DoorAction.SHUTDOWN,DoorAction.LOCK),api.commands)
    }
    @Test fun preferenceSurvivesRestoreAndDoesNotApplyToUnlockOrOtherVehicles() {
        val (repo,api,store)=fixture();repo.setShutdownOnLock(vin,true);repo.restore()
        assertTrue(vin in store.value.shutdownOnLock)
        repo.sendDoor(vin,DoorAction.UNLOCK);repo.select(other);repo.sendDoor(other,DoorAction.LOCK)
        assertEquals(listOf(DoorAction.UNLOCK,DoorAction.LOCK),api.commands)
    }
    @Test fun boundOffUsesBoundVehiclePreference() {
        val (repo,api,_)=fixture(true)
        val binding=BemfaBinding("0123456789abcdef0123456789abcdef","testtopic",vin)
        repo.saveBinding(binding);repo.enableBinding();repo.select(other)
        repo.sendBoundDoor(binding.id,DoorAction.LOCK)
        assertEquals(listOf(DoorAction.SHUTDOWN,DoorAction.LOCK),api.commands)
    }
}
