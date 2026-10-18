package dev.vehiclebridge.byd.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.vehiclebridge.byd.protocol.*
import dev.vehiclebridge.byd.bridge.BemfaBindingCodec
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidSessionStore private constructor(context: Context): SessionStorage {
    companion object {
        @Volatile private var instance: AndroidSessionStore?=null
        fun get(context: Context): AndroidSessionStore = instance ?: synchronized(this) {instance ?: AndroidSessionStore(context.applicationContext).also {instance=it}}
    }
    private val file=AtomicFile(File(context.noBackupFilesDir,"account.vault"))
    private val alias="byd-vehicle-bridge.account.v1"
    private val vault=GcmVault {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        val existing=store.getKey(alias,null) as? SecretKey
        existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setUserAuthenticationRequired(false).build())
        }.generateKey()
    }
    @Synchronized override fun read(): SavedAccount? {
        val input = try { file.openRead() } catch (_: java.io.FileNotFoundException) { return null }
        return try {
            val encrypted=input.use {stream ->
                val bytes=stream.readBytes();require(bytes.size<=65536);bytes.toString(Charsets.UTF_8)
            }
            val record=JSONObject(vault.open(encrypted));
            val pinObject=record.optJSONObject("pins") ?: JSONObject()
            val pins=pinObject.keys().asSequence().associateWith {pinObject.getString(it)}
            require(pins.values.all {Regex("[0-9]{6}").matches(it)})
            val binding=record.optJSONObject("bemfa")?.let {BemfaBindingCodec.decode(it)}
            val unavailable=record.optJSONArray("unsupportedTelemetry")
            val unsupported=if(unavailable==null) emptySet() else (0 until unavailable.length()).map {unavailable.getString(it)}.toSet()
            require(unsupported.all {Regex("[A-HJ-NPR-Z0-9]{17}").matches(it)})
            val conf=record.getJSONObject("account");val sess=record.getJSONObject("session")
            require(record.getString("baseUrl")==CnProtocol.BASE_URL)
            SavedAccount(AccountConfig(conf.getString("username"),conf.getString("password"),conf.getString("targetBrand"),conf.getString("imeiMd5")),
                Session(sess.getString("userId"),sess.optString("superId").takeIf {it.isNotBlank() && it!="null"},sess.getString("signToken"),sess.getString("encryToken"),sess.getLong("createdAt")),record.optString("selectedVin").takeIf {it.isNotBlank() && it!="null"},pins,binding,unsupported,record.optJSONArray("shutdownOnLock")?.let {array -> (0 until array.length()).map {array.getString(it)}.toSet()} ?: emptySet())
        } catch(_:Exception) {throw ApiFailure(null,"无法读取保存的登录信息，请退出登录后重新登录")}
    }
    @Synchronized override fun write(value: SavedAccount) {
        val config=value.config;val session=value.session
        val plain=WireJson.encode(linkedMapOf("shutdownOnLock" to value.shutdownOnLock.toList(),"unsupportedTelemetry" to value.unsupportedTelemetry.toList(),"bemfa" to value.binding?.let {BemfaBindingCodec.encode(it)},"selectedVin" to value.selectedVin,"pins" to value.pins,"baseUrl" to CnProtocol.BASE_URL,"account" to linkedMapOf("username" to config.username,"password" to config.password,"targetBrand" to config.targetBrand,"imeiMd5" to config.imeiMd5),"session" to linkedMapOf("userId" to session.userId,"superId" to session.superId,"signToken" to session.signToken,"encryToken" to session.encryToken,"createdAt" to session.createdAt)))
        try {
            val encrypted=vault.seal(plain).toByteArray(Charsets.UTF_8)
            val stream=file.startWrite()
            try {stream.write(encrypted);file.finishWrite(stream)} catch(e:Exception) {file.failWrite(stream);throw e}
        } catch(_:Exception) {throw ApiFailure(null,"登录信息加密保存失败")}
    }
    @Synchronized override fun clear() {file.delete()}
}
