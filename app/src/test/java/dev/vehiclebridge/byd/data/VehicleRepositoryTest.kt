package dev.vehiclebridge.byd.data

import dev.vehiclebridge.byd.protocol.*
import org.junit.Assert.*
import org.junit.Test

class VehicleRepositoryTest {
    private val one=Vehicle("L0000000000000001","一号","","",true)
    private val two=Vehicle("L0000000000000002","二号","","",false)
    private val config=AccountConfig("synthetic-account","synthetic-password")
    private val session=Session("user","super","sign","content",1L)
    private class Store(var value: SavedAccount?=null): SessionStorage {
        override fun read()=value
        override fun write(value: SavedAccount) {this.value=value}
        override fun clear() {value=null}
    }
    private class Api(var cars: List<Vehicle>): AccountApi {
        val calls=mutableListOf<String>();var rejectPin=false;var failDoor=false
        override fun login(config: AccountConfig)=Session("user","super","sign","content",1L)
        override fun vehicleCount(config: AccountConfig,session: Session)=cars.size
        override fun vehicles(config: AccountConfig,session: Session)=cars
        override fun verifyPin(config: AccountConfig,session: Session,vehicle: Vehicle,pin: String) {
            calls.add("pin");if(rejectPin) throw ApiFailure("5005")
        }
        override fun sendDoor(config: AccountConfig,session: Session,vehicle: Vehicle,pin: String,action: DoorAction) {
            calls.add(action.command);if(failDoor) throw ApiFailure(null,"结果未知")
        }
    }
    @Test fun singleVehicleAutoSelectsButMultipleRequireSelection() {
        assertEquals(one.vin,SessionRepository(Api(listOf(one)),Store()).login(config).selectedVin)
        val repo=SessionRepository(Api(listOf(one,two)),Store())
        assertNull(repo.login(config).selectedVin);assertEquals(two.vin,repo.select(two.vin).selectedVin)
    }
    @Test fun invalidPinPreservesPreviousPinAndStopsCommand() {
        val store=Store(SavedAccount(config,session,one.vin,mapOf(one.vin to "123456")))
        val api=Api(listOf(one));val repo=SessionRepository(api,store);repo.restore();api.rejectPin=true
        assertThrows(ApiFailure::class.java) {repo.savePin(one.vin,"654321")}
        assertEquals("123456",store.value!!.pins[one.vin])
        assertThrows(ApiFailure::class.java) {repo.sendDoor(one.vin,DoorAction.UNLOCK)}
        assertEquals(listOf("pin","pin"),api.calls)
    }
    @Test fun savedPinPersistsAndTemporaryPinNeverPersists() {
        val store=Store();val api=Api(listOf(one,two));val repo=SessionRepository(api,store)
        repo.login(config);repo.select(one.vin);assertTrue(repo.savePin(one.vin,"123456").hasPin)
        val restored=SessionRepository(api,store);assertTrue(restored.restore()!!.hasPin)
        restored.select(two.vin);assertFalse(restored.restore()!!.hasPin)
        restored.sendDoor(two.vin,DoorAction.LOCK,"654321")
        assertFalse(store.value!!.pins.containsKey(two.vin))
    }
    @Test fun ambiguousCommandFailureNeverRetriesAndRemovalBlocksDispatch() {
        val store=Store(SavedAccount(config,session,one.vin,mapOf(one.vin to "123456")))
        val api=Api(listOf(one));val repo=SessionRepository(api,store);repo.restore();api.failDoor=true
        assertThrows(ApiFailure::class.java) {repo.sendDoor(one.vin,DoorAction.LOCK)}
        assertEquals(listOf("pin","LOCKDOOR"),api.calls)
        api.calls.clear();api.cars=emptyList()
        assertThrows(ApiFailure::class.java) {repo.sendDoor(one.vin,DoorAction.UNLOCK)}
        assertTrue(api.calls.isEmpty());assertTrue(store.value!!.pins.isEmpty())
    }
    @Test fun clearedOrMissingPinCannotDispatchAndLogoutClearsSecrets() {
        val store=Store();val api=Api(listOf(one));val repo=SessionRepository(api,store)
        repo.login(config)
        assertThrows(ApiFailure::class.java) {repo.sendDoor(one.vin,DoorAction.LOCK)}
        repo.savePin(one.vin,"123456");repo.removePin(one.vin)
        assertThrows(ApiFailure::class.java) {repo.sendDoor(one.vin,DoorAction.LOCK)}
        repo.logout();assertNull(store.value)
        assertFalse(api.calls.contains("LOCKDOOR"))
    }
}
