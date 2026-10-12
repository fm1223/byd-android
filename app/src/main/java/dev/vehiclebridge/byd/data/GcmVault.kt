package dev.vehiclebridge.byd.data

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import dev.vehiclebridge.byd.protocol.WireJson

/** Key material is supplied by Android Keystore on device, a synthetic key in tests. */
class GcmVault(private val key: () -> SecretKey) {
    private val aad="byd-android-account-v1".toByteArray(Charsets.UTF_8)
    fun seal(plaintext: String): String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(aad)
        val ciphertext=cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return WireJson.encode(linkedMapOf("version" to 1,"iv" to Base64.getEncoder().encodeToString(cipher.iv),"ciphertext" to Base64.getEncoder().encodeToString(ciphertext)))
    }
    fun open(envelope: String): String {
        require(envelope.length<=65536)
        val value=JSONObject(envelope);require(value.getInt("version")==1)
        val iv=Base64.getDecoder().decode(value.getString("iv"));require(iv.size==12)
        val encrypted=Base64.getDecoder().decode(value.getString("ciphertext"));require(encrypted.size>=16)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv));cipher.updateAAD(aad)
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }
}
