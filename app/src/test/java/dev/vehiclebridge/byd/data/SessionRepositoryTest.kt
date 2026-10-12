package dev.vehiclebridge.byd.data
import dev.vehiclebridge.byd.protocol.*
import org.junit.Assert.*
import org.junit.Test
class SessionRepositoryTest {
 private val config=AccountConfig("synthetic-account","synthetic-password")
 private val initial=Session("user",null,"sign","content",1L)
 private class Store(var value: SavedAccount?): SessionStorage {
  var writes=0
  override fun read()=value
  override fun write(value: SavedAccount) {this.value=value;writes++}
  override fun clear() {value=null}
 }
 private class Api(private val replacement: Session): AccountApi {
  var logins=0;var lists=0;var errors=mutableListOf<String?>()
  override fun login(config: AccountConfig): Session {logins++;return replacement}
  override fun vehicles(config: AccountConfig,session: Session): List<Vehicle> {lists++;if(errors.isNotEmpty()) throw ApiFailure(errors.removeAt(0));return listOf(Vehicle("SYNTHETIC1","","","",false),Vehicle("SYNTHETIC2","","","",false))}
  override fun vehicleCount(config: AccountConfig,session: Session): Int = vehicles(config,session).size
 }
 @Test fun restoresWithoutLoginAndWrites() {
  val store=Store(SavedAccount(config,initial));val api=Api(initial)
  val result=SessionRepository(api,store).restore()!!
  assertTrue(result.restored);assertEquals(0,api.logins);assertEquals(0,store.writes)
 }
 @Test fun expiredSessionRelogsOnceAndPersistsReplacement() {
  val replacement=Session("user",null,"new-sign","new-content",2L)
  val store=Store(SavedAccount(config,initial));val api=Api(replacement);api.errors.add("1002")
  val result=SessionRepository(api,store).restore()!!
  assertTrue(result.silentlyLoggedIn);assertEquals(1,api.logins);assertEquals(2,api.lists);assertSame(replacement,store.value!!.session)
 }
 @Test fun ambiguousErrorsNeverRelogOrReplace() {
  for(code in listOf("240","22",null)) {
   val store=Store(SavedAccount(config,initial));val api=Api(initial);api.errors.add(code)
   assertThrows(ApiFailure::class.java) {SessionRepository(api,store).restore()}
   assertEquals(0,api.logins);assertEquals(0,store.writes);assertSame(initial,store.value!!.session)
  }
 }
 @Test fun secondExpiryDoesNotLoopAndPreservesOldSession() {
  val store=Store(SavedAccount(config,initial));val api=Api(initial);api.errors.addAll(listOf("1002","1002"))
  assertThrows(ApiFailure::class.java) {SessionRepository(api,store).restore()}
  assertEquals(1,api.logins);assertEquals(0,store.writes)
 }
 @Test fun missingCacheAndLogoutDoNotContactServer() {
  val store=Store(null);val api=Api(initial);val repo=SessionRepository(api,store)
  assertNull(repo.restore());repo.logout();assertEquals(0,api.logins);assertEquals(0,api.lists)
 }
}
