package dev.vehiclebridge.byd.protocol
import org.junit.Assert.*
import org.junit.Test
class CnCryptoTest {
 @Test fun matchesNodeContentEncryption() {
  val plaintext = """{"test":"车辆","value":1}"""
  val key = "00112233445566778899aabbccddeeff"
  val expected = "59DBB254F47EA33AA877754FF01F690D2E205DE5F34237DE864E74C4FF7B4F1E"
  assertEquals(expected, CnCrypto.encryptContent(plaintext, key))
  assertEquals(plaintext, CnCrypto.decryptContent(expected, key))
  assertEquals("1b9227d463e867c0fc009ba5873a5d42500b48c066abdffe62ebfbb9b8100f42", CnCrypto.checkcode(plaintext))
 }
 @Test fun matchesNodeSignature() {
  val fields = mapOf("timeStamp" to "1700000000000", "identifier" to "synthetic-account", "random" to "00112233445566778899AABBCCDDEEFF")
  assertEquals("E64D967207359daD920C49080b943b1CE3eD96e", CnCrypto.sign(fields, "synthetic-key"))
 }
 @Test fun validatesPin() {
  assertEquals("E10ADC3949BA59ABBE56E057F20F883E", CnCrypto.operationPinHash("123456"))
  assertThrows(IllegalArgumentException::class.java) { CnCrypto.operationPinHash("12345") }
 }
}
