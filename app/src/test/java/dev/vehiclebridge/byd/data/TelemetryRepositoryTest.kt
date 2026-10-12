package dev.vehiclebridge.byd.data
import dev.vehiclebridge.byd.protocol.*
import org.junit.Assert.*
import org.junit.Test

class TelemetryRepositoryTest {
    @Test fun unsupportedReportHidesOnlyThatVehicleAndDoesNotRetryUntilLoginReset() {
        val one=Vehicle("L0000000000000001","One","","",true)
        val two=Vehicle("L0000000000000002","Two","","",false)
        val config=AccountConfig("synthetic-account","synthetic-password")
        val session=Session("user","super","sign","content",1L)
        val store=object: SessionStorage {
            var account: SavedAccount?=SavedAccount(config,session,one.vin)
            override fun read()=account
            override fun write(value: SavedAccount) {account=value}
            override fun clear() {account=null}
        }
        var code="1001";var calls=0
        val api=object: AccountApi {
            override fun login(config: AccountConfig)=session
            override fun vehicleCount(config: AccountConfig,session: Session)=2
            override fun vehicles(config: AccountConfig,session: Session)=listOf(one,two)
            override fun telemetry(config: AccountConfig,session: Session,vehicle: Vehicle): VehicleTelemetry {calls++;throw ApiFailure(code)}
        }
        val repo=SessionRepository(api,store);repo.restore()
        assertThrows(ApiFailure::class.java) {repo.refreshTelemetry(one.vin)}
        assertEquals(setOf(one.vin),repo.snapshot()!!.unsupportedTelemetry)
        assertThrows(ApiFailure::class.java) {repo.refreshTelemetry(one.vin)}
        assertEquals(1,calls)
        assertEquals(setOf(one.vin),SessionRepository(api,store).restore()!!.unsupportedTelemetry)
        repo.select(two.vin);code="240"
        assertThrows(ApiFailure::class.java) {repo.refreshTelemetry(two.vin)}
        assertFalse(two.vin in repo.snapshot()!!.unsupportedTelemetry)
        assertEquals(setOf(one.vin),repo.restore()!!.unsupportedTelemetry)
        repo.logout();assertTrue(repo.login(config).unsupportedTelemetry.isEmpty())
    }

    @Test fun reportsStayWithTheirVehicleAndFailedRefreshPreservesPriorData() {
        val one=Vehicle("L0000000000000001","One","","",true)
        val two=Vehicle("L0000000000000002","Two","","",false)
        val config=AccountConfig("synthetic-account","synthetic-password")
        val session=Session("user","super","sign","content",1L)
        val store=object: SessionStorage {
            var account: SavedAccount?=SavedAccount(config,session,one.vin)
            override fun read()=account
            override fun write(value: SavedAccount) {account=value}
            override fun clear() {account=null}
        }
        var reject=false;val requested=mutableListOf<String>()
        val api=object: AccountApi {
            override fun login(config: AccountConfig)=session
            override fun vehicleCount(config: AccountConfig,session: Session)=2
            override fun vehicles(config: AccountConfig,session: Session)=listOf(one,two)
            override fun telemetry(config: AccountConfig,session: Session,vehicle: Vehicle): VehicleTelemetry {
                requested.add(vehicle.vin)
                if(reject) throw ApiFailure("240")
                return VehicleTelemetry(listOf("总里程" to "123 km"))
            }
        }
        val repository=SessionRepository(api,store);repository.restore()
        assertTrue(repository.refreshTelemetry(one.vin).telemetry.containsKey(one.vin))
        repository.select(two.vin)
        assertFalse(repository.snapshot()!!.telemetry.containsKey(two.vin))
        assertThrows(ApiFailure::class.java) {repository.refreshTelemetry(one.vin)}
        assertEquals(listOf(one.vin),requested)
        repository.select(one.vin);reject=true
        assertThrows(ApiFailure::class.java) {repository.refreshTelemetry(one.vin)}
        assertEquals("123 km",repository.snapshot()!!.telemetry[one.vin]!!.rows.single().second)
        repository.logout();reject=false
        assertTrue(repository.login(config).telemetry.isEmpty())
    }
}
