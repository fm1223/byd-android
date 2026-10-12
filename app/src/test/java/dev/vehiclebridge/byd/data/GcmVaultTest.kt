package dev.vehiclebridge.byd.data
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import javax.crypto.spec.SecretKeySpec
class GcmVaultTest {
 @Test fun encryptsRandomlyAndDetectsTampering() {
  val vault=GcmVault {SecretKeySpec(ByteArray(32){it.toByte()},"AES")}
  val plain="synthetic-account-password-token"
  val a=vault.seal(plain);val b=vault.seal(plain)
  assertNotEquals(a,b);assertFalse(a.contains("synthetic"));assertEquals(plain,vault.open(a))
  val value=JSONObject(a);val ciphertext=value.getString("ciphertext")
  value.put("ciphertext",(if(ciphertext[0]=='A') "B" else "A")+ciphertext.substring(1))
  assertThrows(Exception::class.java) {vault.open(value.toString())}
  val wrong=GcmVault {SecretKeySpec(ByteArray(32){42},"AES")}
  assertThrows(Exception::class.java) {wrong.open(a)}
 }
}
