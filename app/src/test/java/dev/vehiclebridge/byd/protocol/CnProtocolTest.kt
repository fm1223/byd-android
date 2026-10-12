package dev.vehiclebridge.byd.protocol
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
class CnProtocolTest {
 private val config = AccountConfig("synthetic-account","synthetic-password")
 private val protocol = CnProtocol(config,{1700000000000L},{"00112233445566778899AABBCCDDEEFF"})
 @Test fun matchesFullNodeLoginRequest() {
  assertEquals("""{"appChannel":"99","encryData":"4F209F3267A15F06040C06413FFEA95EDF01F9A4477F955BFB74EDAD72C075FE95CD3EB00857D693A4DD350FC84B56982D5A4F27F7B31EC739BC9170C9C77E1BDFA2A0B390673BB72539CDA737F52D0AEA9642F3E152F1FB921735779347331B6EF84CC9981750D90E9E8515AA68A9F72D90BD852B113E898DF667D18FB978861E13A7CFCEC908AC2173618199B306FEAC448F2E536E359C8114F574944741658B0D9AE5CA4FE7D96FA02B6B3A897F2868D8B9C2ECBBFB949C38A0B253F9BD5E2C43887EA97BB494C9D5399C78FA24FC919B23C5E672F3C765B0F2726928DB93FCC29A29F17713B6A9AEAD18D129B9CB865768F86059A920D2A5A9AFF96A2E9870902160B9C2F91A5B0E90BC3150F7EF56D5DECE9DE6877FA8585174EEF32D38CB48FCF2544B60B7A2DCE6B0FD3D3E6B90F4FCC9F75D1874BD709CE59BAC15A3EA850E739633CBE2049808359D9ADF327EAADA7395C4FD838BC6EF2F1CD725EF3490760A445ECAB265EBF4F666714E2B6E12E96D330022463619E9DA3789BCD6AF1D31FE93644827DF04D5A53B1E6F5198041F1EE4E5F52FCAA8343E18093A6350A72689D16EBD8F1D6EBC92B1FD5DABACE811BD4B12B19B80DB1581D12CD5BD","identifier":"synthetic-account","imeiMD5":"4AB89A2B569F0B0C3AFA4D39D78669F8","isAuto":"0","loginType":0,"reqTimestamp":"1700000000000","sign":"6Cd0A314A5d71156E16eD2de1Ec4E3ccF0eaDD5f","targetBrand":"1","ostype":"and","imei":"BANGCLE01234","mac":"00:00:00:00:00:00","model":"POCO F1","sdk":"35","serviceTime":"1700000000000","mod":"Xiaomi","checkcode":"996d1a53fc50d2b3e2bf209740ecb420c9bb5a86a0565b7bd3b4b3dfb613f4a6"}""",protocol.loginRequest())
 }
 @Test fun matchesFullNodeVehicleListRequest() {
  val session=Session("synthetic-user","synthetic-super","synthetic-sign","synthetic-content",1700000000000L)
  assertEquals("""{"appChannel":"99","encryData":"386AF958381E304E791E81C1D300D2B50FD9410DA22360C8E311A2ED3C759CA937C03E81ACD503C6E968449E623E98CB2E0D7AB50FD7B25D714281D8313391AD260688C5F2759ADAC36DC7AB8C28FC96891CDC8D07D0E4C8FBB2F26F7581B89E01BEED0728A735B68EB1A8D8E8E48C6647F6891ABB2C919A944D8389697A40BC8CCCEE68AE0FDDC7B68D18C42375D4902ABD26DBE5F409E5AC6A2526E4A4062CBD647EFCD54131A4D66AF1A811EEB6209DEBE575E5CB28D3415ED7B0711419CDE7DC1F3A852F3A0C5462BF79C401241C83F9E8A1C868B20848CCE003A1E5B3E6C138A8836040252630B934737399F57E390503130F2680F1152DD51306D7C1A3EED7D338B79481D6F630B696F47DBB50DAF8A63ED598A1EE40EBE79ADB67BCF1AAC725B358499BB8ABECC3B07D2E691EC03E253D23DF92181CC00D4945A7AE628E2F1D63082C19D0CD2C10571B51D4D03F31EEA9D3D87350208E7306E3FBDAA1","identifier":"synthetic-super","identifierType":2,"imeiMD5":"4AB89A2B569F0B0C3AFA4D39D78669F8","objective":null,"outModelTypes":null,"reqTimestamp":"1700000000000","sign":"12a9C24746ab4257CAb46C411554CcaF297BCf","softType":null,"targetBrand":"1","vehicleBrand":"1","version":null,"ostype":"and","imei":"BANGCLE01234","mac":"00:00:00:00:00:00","model":"POCO F1","sdk":"35","serviceTime":"1700000000000","mod":"Xiaomi","checkcode":"f69f6cd4ae0afd67c87f4caff6da33d48cf809efa12342cfc8797d727e7e6271"}""",protocol.listRequest(session))
 }
 @Test fun parsesChinaBrandSessionAndAlternateEncryptionToken() {
  val inner="""{"token":{"superId":"super","superBindRelationDtoMap":{"1":{"userId":"brand"}},"signToken":"sign","encryptToken":"content"}}"""
  val outer=JSONObject().put("code","0").put("respondData",CnCrypto.encryptContent(inner,CnCrypto.loginContentKey("synthetic-password")))
  val session=protocol.loginResponse(outer)
  assertEquals("brand",session.userId);assertEquals("super",session.identifier);assertEquals("content",session.encryToken)
  assertEquals("Session([redacted])",session.toString())
 }
 @Test fun separatesExpiryFromPermissionOrSecurityNotification() {
  assertTrue(ApiFailure("1002").sessionExpired)
  assertFalse(ApiFailure("240").sessionExpired)
  assertFalse(ApiFailure("22").sessionExpired)
 }
 @Test fun decodesVehicleListAndAcceptsNotificationWithData() {
  val session=Session("user",null,"sign","content",1L)
  val plain="""{"diLinkAutoInfoList":[{"vin":"SYNTHETIC"},{}]}"""
  val outer=JSONObject().put("code","22").put("respondData",CnCrypto.encryptContent(plain,CnCrypto.md5Upper("content")))
  assertEquals(1,protocol.listResponse(outer,session))
  assertThrows(ApiFailure::class.java) {protocol.listResponse(JSONObject().put("code","240"),session)}
 }
 @Test fun serializesEscapesAndNullExactly() {
  val objectValue=linkedMapOf<String,Any?>("a" to "quote\"slash\\line\n", "b" to null)
  assertEquals(objectValue["a"],JSONObject(WireJson.encode(objectValue)).getString("a"))
  assertTrue(JSONObject(WireJson.encode(objectValue)).isNull("b"))
 }

 @Test fun showsServerLoginFailureMessage() {
  val failure=assertThrows(ApiFailure::class.java) {
   protocol.loginResponse(JSONObject().put("code","1").put("message","账号或密码错误，请重试"))
  }
  assertEquals("1",failure.code)
  assertEquals("账号或密码错误，请重试",failure.label)
 }
 @Test fun loginFailureMessageRedactsSecretsAndFallsBackWhenAbsent() {
  val failure=assertThrows(ApiFailure::class.java) {
   protocol.loginResponse(JSONObject().put("code","1").put("message","错误 synthetic-account synthetic-password 13812345678 ABCDEF1234567890ABCDEF1234567890"))
  }
  assertFalse(failure.label.contains(config.username));assertFalse(failure.label.contains(config.password))
  assertFalse(failure.label.contains("13812345678"));assertFalse(failure.label.contains("ABCDEF"))
  val missing=assertThrows(ApiFailure::class.java) {protocol.loginResponse(JSONObject().put("code","1"))}
  assertEquals("服务器拒绝请求",missing.label)
 }
}
