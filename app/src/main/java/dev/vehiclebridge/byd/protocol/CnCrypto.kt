package dev.vehiclebridge.byd.protocol

import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Primitives for the China App protocol. Exact serialized bytes are significant. */
object CnCrypto {
    private fun digest(algorithm: String, value: String): ByteArray =
        MessageDigest.getInstance(algorithm).digest(value.toByteArray(Charsets.UTF_8))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun md5Upper(value: String): String = hex(digest("MD5", value)).uppercase(Locale.ROOT)
    fun checkcode(serializedJson: String): String = hex(digest("SHA-256", serializedJson))
    fun loginContentKey(password: String): String = md5Upper(md5Upper(password))
    fun operationPinHash(pin: String): String {
        require(Regex("[0-9]{6}").matches(pin)) { "Operation PIN must contain six digits" }
        return md5Upper(pin)
    }
    fun sign(fields: Map<String, String>, passwordKey: String): String {
        val value = fields.toSortedMap().entries.joinToString("&") { (key, item) -> key + "=" + item } + "&password=" + passwordKey
        val mixed = digest("SHA-1", value).mapIndexed { index, byte ->
            val h = "%02x".format(byte.toInt() and 255)
            if (index % 2 == 0) h.uppercase(Locale.ROOT) else h
        }.joinToString("")
        return mixed.filterIndexed { index, c -> !(c == '0' && index % 2 == 0) }
    }
    private fun decodeHex(value: String): ByteArray {
        require(value.length % 2 == 0 && Regex("[0-9a-fA-F]*").matches(value)) { "Invalid hex" }
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    private fun aes(mode: Int, input: ByteArray, keyHex: String): ByteArray {
        val key = decodeHex(keyHex)
        require(key.size == 16) { "Expected AES-128 key" }
        return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
            doFinal(input)
        }
    }
    fun encryptContent(plaintext: String, keyHex: String): String =
        hex(aes(Cipher.ENCRYPT_MODE, plaintext.toByteArray(Charsets.UTF_8), keyHex)).uppercase(Locale.ROOT)
    fun decryptContent(cipherHex: String, keyHex: String): String =
        aes(Cipher.DECRYPT_MODE, decodeHex(cipherHex), keyHex).toString(Charsets.UTF_8)
}
