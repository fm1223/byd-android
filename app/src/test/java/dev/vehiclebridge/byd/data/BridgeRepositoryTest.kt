package dev.vehiclebridge.byd.data

import dev.vehiclebridge.byd.bridge.*
import dev.vehiclebridge.byd.protocol.*
import org.junit.Assert.*
import org.junit.Test

class BridgeRepositoryTest {
    private val one=Vehicle("L0000000000000001","一号","","",true)
    private val two=Vehicle("L0000000000000002","二号","","",false)
    private val config=AccountConfig("synthetic-account","synthetic-password")
    private val session=Session("user","super","sign","content",1L)
    private val binding=BemfaBinding("00112233445566778899aabbccddeeff","car002",one.vin)
    private class Store(var value: SavedAccount): SessionStorage {
        override fun read()=value
        override fun write(value: SavedAccount) {this.value=value}
        override fun clear() {}
    }
    private inner class Api: AccountApi {
        var cars=listOf(one,two);val targets=mutableListOf<String>();var expire=false
        override fun login(config: AccountConfig)=session
        override fun vehicleCount(config: AccountConfig,session: Session)=cars.size
        override fun vehicles(config: AccountConfig,session: Session): List<Vehicle> {
            if(expire) {expire=false;throw ApiFailure("1002")};return cars
        }
        override fun verifyPin(config: AccountConfig,session: Session,vehicle: Vehicle,pin: String) {targets.add("verify:"+vehicle.vin)}
        override fun sendDoor(config: AccountConfig,session: Session,vehicle: Vehicle,pin: String,action: DoorAction) {targets.add(vehicle.vin)}
    }
    private fun saved()=SavedAccount(config,session,one.vin,mapOf(one.vin to "123456",two.vin to "654321"))
    @Test fun targetBindingSurvivesHomeSelectionAndSessionRenewal() {
        val store=Store(saved());val api=Api();val repo=SessionRepository(api,store)
        repo.restore();repo.saveBinding(binding);repo.enableBinding();repo.select(two.vin)
        api.expire=true;repo.sendBoundDoor(binding.id,DoorAction.UNLOCK)
        assertEquals(listOf("verify:"+one.vin,one.vin),api.targets)
        assertEquals(two.vin,store.value.selectedVin);assertTrue(store.value.binding!!.enabled)
    }
    @Test fun stoppedOrReplacedBindingCannotDispatchOldMessage() {
        val store=Store(saved());val api=Api();val repo=SessionRepository(api,store)
        repo.restore();repo.saveBinding(binding);repo.enableBinding();repo.disableBinding()
        assertThrows(ApiFailure::class.java) {repo.sendBoundDoor(binding.id,DoorAction.UNLOCK)}
        val replacement=BemfaBinding(binding.key,"other002",one.vin)
        repo.saveBinding(replacement);repo.enableBinding()
        assertThrows(ApiFailure::class.java) {repo.sendBoundDoor(binding.id,DoorAction.LOCK)}
        assertTrue(api.targets.isEmpty())
    }
    @Test fun missingPinRejectsBindingAndRemovingPinDisablesExistingBinding() {
        val store=Store(saved());val api=Api();val repo=SessionRepository(api,store)
        repo.restore();repo.removePin(one.vin)
        assertThrows(ApiFailure::class.java) {repo.saveBinding(binding)}
        repo.savePin(one.vin,"123456");repo.saveBinding(binding);repo.enableBinding()
        repo.removePin(one.vin);assertFalse(store.value.binding!!.enabled)
        assertThrows(ApiFailure::class.java) {repo.enableBinding()}
    }
    @Test fun vehicleRemovalDisablesBridgeAndNeverSendsCommand() {
        val store=Store(saved());val api=Api();val repo=SessionRepository(api,store)
        repo.restore();repo.saveBinding(binding);repo.enableBinding();api.cars=listOf(two)
        assertThrows(ApiFailure::class.java) {repo.sendBoundDoor(binding.id,DoorAction.UNLOCK)}
        assertFalse(store.value.binding!!.enabled);assertTrue(api.targets.isEmpty())
    }
}
