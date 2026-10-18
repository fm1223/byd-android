package dev.vehiclebridge.byd.data

import dev.vehiclebridge.byd.protocol.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VehicleStatusRepositoryTest {
    private val vehicle=Vehicle("L0000000000000001","","","",true)
    private val config=AccountConfig("synthetic-account","synthetic-password")
    private val session=Session("synthetic-user",null,"synthetic-sign","synthetic-content",1L)
    private val now=1_800_000_000_000L
    @Test fun postControlReportBoundarySurvivesManualRefreshUntilNewReportArrives() {
        var time=now
        val api=object: AccountApi {
            override fun login(config: AccountConfig)=session
            override fun vehicleCount(config: AccountConfig,session: Session)=1
            override fun vehicles(config: AccountConfig,session: Session)=listOf(vehicle)
            override fun fetchVehicleStatus(config: AccountConfig,session: Session,vehicle: Vehicle): VehicleStatus {
                val data=JSONObject().put("time",time).put("leftFrontDoorLock",2).put("rightFrontDoorLock",2).put("leftRearDoorLock",2).put("rightRearDoorLock",2).put("trunkLid",0)
                return VehicleStatus(VehicleStateReport(data))
            }
        }
        val storage=object: SessionStorage {
            var value: SavedAccount?=SavedAccount(config,session,vehicle.vin)
            override fun read()=value
            override fun write(value: SavedAccount) {this.value=value}
            override fun clear() {value=null}
        }
        val repo=SessionRepository(api,storage);repo.restore()
        val after=repo.refreshAfterControl(vehicle.vin,now+1).vehicleStatus!!
        assertTrue(after.awaitingReport);assertNull(after.doorAction(now))
        val unchanged=repo.refreshVehicleStatus(vehicle.vin).vehicleStatus!!
        assertTrue(unchanged.awaitingReport);assertNull(unchanged.trunkAction(now))
        time=now+1000
        val updated=repo.refreshVehicleStatus(vehicle.vin).vehicleStatus!!
        assertFalse(updated.awaitingReport);assertEquals(DoorAction.UNLOCK,updated.doorAction(time))
    }

    private inner class Fixture {
        var listCalls=0;var loginCalls=0;var statusCalls=0
        var expired=false;var failCode: String?=null
        val api=object: AccountApi {
            override fun login(config: AccountConfig): Session {loginCalls++;expired=false;return session}
            override fun vehicleCount(config: AccountConfig,session: Session)=1
            override fun vehicles(config: AccountConfig,session: Session): List<Vehicle> {
                listCalls++;if(expired) throw ApiFailure("1002");return listOf(vehicle)
            }
            override fun fetchVehicleStatus(config: AccountConfig,session: Session,vehicle: Vehicle): VehicleStatus {
                statusCalls++;if(expired) throw ApiFailure("1002")
                failCode?.let {throw ApiFailure(it)}
                return VehicleStatus(VehicleStateReport(JSONObject().put("trunkLid",0)))
            }
        }
        val store=object: SessionStorage {
            var value: SavedAccount?=SavedAccount(config,session,vehicle.vin)
            override fun read()=value
            override fun write(value: SavedAccount) {this.value=value}
            override fun clear() {value=null}
        }
        val repo=SessionRepository(api,store).also {it.restore()}
    }
    @Test fun healthyManualAndPostControlRefreshNeverFetchVehicleList() {
        val f=Fixture()
        f.repo.refreshVehicleStatus(vehicle.vin);f.repo.refreshVehicleStatus(vehicle.vin);f.repo.refreshAfterControl(vehicle.vin,now)
        assertEquals(1,f.listCalls);assertEquals(0,f.loginCalls);assertEquals(3,f.statusCalls)
    }
    @Test fun explicitExpiryRecoversSessionAndRetriesOnlyTheReadOnce() {
        val f=Fixture();f.expired=true
        val result=f.repo.refreshVehicleStatus(vehicle.vin)
        assertNotNull(result.vehicleStatus!!.report);assertEquals(1,f.loginCalls);assertEquals(2,f.statusCalls)
    }
    @Test fun rejectedStatusDoesNotLoginOrRefreshListAndPersistentExpiryIsBounded() {
        val f=Fixture();f.failCode="240"
        assertNotNull(f.repo.refreshVehicleStatus(vehicle.vin).vehicleStatus!!.vehicleError)
        assertEquals(1,f.listCalls);assertEquals(0,f.loginCalls);assertEquals(1,f.statusCalls)
        f.failCode="1002"
        assertNotNull(f.repo.refreshVehicleStatus(vehicle.vin).vehicleStatus!!.vehicleError)
        assertEquals(2,f.listCalls);assertEquals(0,f.loginCalls);assertEquals(3,f.statusCalls)
    }
}
