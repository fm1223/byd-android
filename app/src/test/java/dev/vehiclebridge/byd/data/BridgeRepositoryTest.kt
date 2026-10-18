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
        val actions=mutableListOf<DoorAction>()
        var cars=listOf(one,two);val targets=mutableListOf<String>();var expire=false
        override fun login(config: AccountConfig)=session
        override fun vehicleCount(config: AccountConfig,session: Session)=cars.size
        override fun vehicles(config: AccountConfig,session: Session): List<Vehicle> {
            if(expire) {expire=false;throw ApiFailure("1002")};return cars
        }
        override fun verifyPin(config: AccountConfig,session: Session,vehicle: Vehicle,pin: String) {targets.add("verify:"+vehicle.vin)}
        override fun sendDoor(config: AccountConfig,session: Session,vehicle: Vehicle,pin: String,action: DoorAction) {targets.add(vehicle.vin);actions.add(action)}
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

    @Test fun multipleDevicesRouteToTheirOwnVehicleAndFunctionAcrossRestore() {
        val store=Store(saved());val api=Api();val repo=SessionRepository(api,store);repo.restore()
        val door=BemfaDevice("door001",one.vin);val trunk=BemfaDevice("trunk001",two.vin,BemfaFunction.TRUNK)
        repo.saveDevice(binding.key,door);repo.saveDevice(binding.key,trunk);repo.enableBinding();repo.restore()
        repo.select(one.vin);repo.sendBoundDoor(trunk.id,DoorAction.OPEN_TRUNK)
        repo.sendBoundDoor(door.id,DoorAction.LOCK)
        assertEquals(listOf("verify:"+two.vin,two.vin,"verify:"+one.vin,one.vin),api.targets)
        assertEquals(listOf(DoorAction.OPEN_TRUNK,DoorAction.LOCK),api.actions)
        assertEquals(2,store.value.binding!!.devices.size)
    }
    @Test fun duplicateTopicAndWrongFunctionNeverDispatch() {
        val store=Store(saved());val api=Api();val repo=SessionRepository(api,store);repo.restore()
        val door=BemfaDevice("car001",one.vin);repo.saveDevice(binding.key,door);repo.enableBinding()
        assertThrows(ApiFailure::class.java) {repo.saveDevice(binding.key,BemfaDevice("car001",two.vin,BemfaFunction.TRUNK))}
        assertThrows(ApiFailure::class.java) {repo.sendBoundDoor(door.id,DoorAction.CLOSE_TRUNK)}
        assertTrue(api.targets.isEmpty());assertEquals(1,store.value.binding!!.devices.size)
    }
    @Test fun editsAndDisabledOrDeletedDevicesRejectStaleMessages() {
        val store=Store(saved());val api=Api();val repo=SessionRepository(api,store);repo.restore()
        val device=BemfaDevice("one001",one.vin);repo.saveDevice(binding.key,device);repo.enableBinding()
        val oldConfig=store.value.binding!!.id
        val replacement=BemfaDevice("two001",two.vin,BemfaFunction.TRUNK)
        repo.saveDevice(binding.key,replacement,device.id)
        assertTrue(store.value.binding!!.enabled)
        assertThrows(ApiFailure::class.java) {repo.sendBoundDoor(device.id,DoorAction.LOCK)}
        assertThrows(ApiFailure::class.java) {repo.sendBoundDoor(replacement.id,DoorAction.OPEN_TRUNK,configurationId=oldConfig)}
        repo.setDeviceEnabled(replacement.id,false)
        assertThrows(ApiFailure::class.java) {repo.sendBoundDoor(replacement.id,DoorAction.CLOSE_TRUNK)}
        repo.removeDevice(replacement.id);assertNull(store.value.binding)
        assertTrue(api.targets.isEmpty())
    }
    @Test fun removingOnePinDisablesOnlyThatVehiclesDevices() {
        val store=Store(saved());val api=Api();val repo=SessionRepository(api,store);repo.restore()
        val door=BemfaDevice("door001",one.vin);val trunk=BemfaDevice("trunk001",two.vin,BemfaFunction.TRUNK)
        repo.saveDevice(binding.key,door);repo.saveDevice(binding.key,trunk);repo.enableBinding()
        repo.removePin(one.vin);repo.restore()
        assertTrue(store.value.binding!!.enabled);assertEquals(listOf(trunk.id),store.value.binding!!.activeDevices.map {it.id})
        repo.sendBoundDoor(trunk.id,DoorAction.CLOSE_TRUNK)
        assertEquals(listOf(DoorAction.CLOSE_TRUNK),api.actions)
        repo.setDeviceEnabled(trunk.id,false);assertFalse(store.value.binding!!.enabled)
    }
}
