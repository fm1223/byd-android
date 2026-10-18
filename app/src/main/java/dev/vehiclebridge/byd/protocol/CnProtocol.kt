package dev.vehiclebridge.byd.protocol

import org.json.JSONObject
import java.security.SecureRandom

class AccountConfig(val username: String, val password: String, val targetBrand: String = "1", val imeiMd5: String = CnCrypto.md5Upper(username)) {
    init { require(username.isNotBlank() && password.isNotEmpty()); require(targetBrand in listOf("1","2","3","4","5")); require(Regex("[A-Fa-f0-9]{32}").matches(imeiMd5)) }
    override fun toString() = "AccountConfig([redacted])"
}
class Session(val userId: String, val superId: String?, val signToken: String, val encryToken: String, val createdAt: Long) {
    init { require(userId.isNotBlank() && signToken.isNotBlank() && encryToken.isNotBlank()) }
    val identifier get() = superId?.takeIf { it.isNotBlank() } ?: userId
    override fun toString() = "Session([redacted])"
}
class ApiFailure(val code: String?, val label: String = "服务器拒绝请求") : Exception(label) {
    val sessionExpired get() = code in setOf("1002","1005","1010")
}
class CnProtocol(private val config: AccountConfig, private val clock: () -> Long = System::currentTimeMillis, private val nonce: () -> String = ::randomNonce) {
    companion object {
        const val BASE_URL = "https://dilinksuperappserver-cn.byd.auto"
        const val INNER_VERSION = "502"
        const val APP_VERSION = "9.10.2"
        private fun randomNonce(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it.toInt() and 255) }
    }
    private fun outerDevice(outer: LinkedHashMap<String,Any?>, now: Long): LinkedHashMap<String,Any?> {
        outer.putAll(linkedMapOf("ostype" to "and","imei" to "BANGCLE01234","mac" to "00:00:00:00:00:00","model" to "POCO F1","sdk" to "35","serviceTime" to now.toString(),"mod" to "Xiaomi"))
        outer.remove("checkcode")
        outer["checkcode"] = CnCrypto.checkcode(WireJson.encode(outer))
        return outer
    }
    fun loginRequest(): String {
        val now = clock(); val timestamp = now.toString()
        val inner = linkedMapOf<String,Any?>("appInnerVersion" to INNER_VERSION,"appVersion" to APP_VERSION,"bluetoothMac" to "","city" to "","configVersion" to "10000","deviceType" to "0","devicename" to "XIAOMIPOCO F1","imeiMD5" to config.imeiMd5,"isAuto" to "0","latitude" to "","longitude" to "","mobileBrand" to "XIAOMI","mobileModel" to "POCO F1","networkOperator" to "无","networkType" to "wifi","osType" to "Android","osVersion" to "15","random" to nonce(),"softType" to "0","timeStamp" to timestamp)
        val fields = LinkedHashMap(inner).apply { putAll(linkedMapOf("appChannel" to "99","identifier" to config.username,"loginType" to 0,"reqTimestamp" to timestamp,"targetBrand" to config.targetBrand)) }
        val sign = CnCrypto.sign(fields.mapValues { it.value.toString() },CnCrypto.md5Upper(config.password))
        return WireJson.encode(outerDevice(linkedMapOf("appChannel" to "99","encryData" to CnCrypto.encryptContent(WireJson.encode(inner),CnCrypto.loginContentKey(config.password)),"identifier" to config.username,"imeiMD5" to config.imeiMd5,"isAuto" to "0","loginType" to 0,"reqTimestamp" to timestamp,"sign" to sign,"targetBrand" to config.targetBrand),now))
    }
    fun listRequest(session: Session): String {
        val now=clock();val timestamp=now.toString()
        val inner=linkedMapOf<String,Any?>("appUiName" to "","deviceName" to "XIAOMIPOCO F1","deviceType" to "0","imeiMD5" to config.imeiMd5,"mobileBrand" to "XIAOMI","mobileModel" to "POCO F1","networkOperator" to "无","networkType" to "wifi","osType" to "Android","osVersion" to "35","random" to nonce(),"softType" to "0","timeStamp" to timestamp,"version" to INNER_VERSION)
        val fields=LinkedHashMap(inner).apply {putAll(linkedMapOf("appChannel" to "99","identifier" to session.identifier,"identifierType" to 2,"imeiMD5" to config.imeiMd5,"reqTimestamp" to timestamp,"targetBrand" to config.targetBrand,"vehicleBrand" to config.targetBrand))}
        val sign=CnCrypto.sign(fields.mapValues {it.value.toString()},CnCrypto.md5Upper(session.signToken))
        return WireJson.encode(outerDevice(linkedMapOf("appChannel" to "99","encryData" to CnCrypto.encryptContent(WireJson.encode(inner),CnCrypto.md5Upper(session.encryToken)),"identifier" to session.identifier,"identifierType" to 2,"imeiMD5" to config.imeiMd5,"objective" to null,"outModelTypes" to null,"reqTimestamp" to timestamp,"sign" to sign,"softType" to null,"targetBrand" to config.targetBrand,"vehicleBrand" to config.targetBrand,"version" to null),now))
    }
    private fun loginFailureMessage(outer: JSONObject): String {
        val raw = listOf("message", "msg", "errorMessage", "errmsg")
            .mapNotNull { outer.opt(it) as? String }.firstOrNull { it.isNotBlank() }
            ?: return "服务器拒绝请求"
        var message = raw
        // Server messages are untrusted: never echo credentials or identifiers.
        val secrets = listOf(config.username, config.password, config.imeiMd5,
            CnCrypto.md5Upper(config.password), CnCrypto.loginContentKey(config.password))
        secrets.filter { it.isNotEmpty() }.sortedByDescending { it.length }.forEach {
            message = message.replace(it, "[已隐藏]")
        }
        message = message.replace(Regex("[\\p{Cntrl}]"), " ")
            .replace(Regex("[A-HJ-NPR-Z0-9]{17}", RegexOption.IGNORE_CASE), "[车辆标识已隐藏]")
            .replace(Regex("[0-9]{11,}"), "[账号已隐藏]")
            .replace(Regex("[A-Za-z0-9_+/=-]{24,}(?:[.][A-Za-z0-9_+/=-]+)*"), "[已隐藏]")
            .trim().take(300)
        return message.ifBlank { "服务器拒绝请求" }
    }
    private fun innerResponse(outer: JSONObject, key: String): JSONObject {
        val code=outer.optString("code")
        val payload=outer.optString("respondData")
        if(code!="0" && !(code=="22" && payload.isNotBlank())) throw ApiFailure(code.takeIf { Regex("[0-9]{1,6}").matches(it) }, loginFailureMessage(outer))
        return try { JSONObject(CnCrypto.decryptContent(payload,key)) } catch(_:Exception) {throw ApiFailure(null,"响应解析失败")}
    }
    fun loginResponse(outer: JSONObject): Session {
        val token=innerResponse(outer,CnCrypto.loginContentKey(config.password)).optJSONObject("token") ?: throw ApiFailure(null,"登录响应缺少会话")
        val superId=token.optString("superId").takeIf {it.isNotBlank() && it!="null"}
        val brandId=token.optJSONObject("superBindRelationDtoMap")?.optJSONObject(config.targetBrand)?.optString("userId")?.takeIf {it.isNotBlank() && it!="null"}
        return try { Session(brandId ?: superId ?: "",superId,token.optString("signToken"),token.optString("encryToken").ifBlank {token.optString("encryptToken")},clock()) } catch(_:Exception) {throw ApiFailure(null,"登录响应缺少会话")}
    }
    fun vehiclesResponse(outer: JSONObject, session: Session): List<Vehicle> {
        val code=outer.optString("code");val payload=outer.optString("respondData")
        if(code!="0" && !(code=="22" && payload.isNotBlank())) throw ApiFailure(code.takeIf {Regex("[0-9]{1,6}").matches(it)})
        return try {
            val plain=CnCrypto.decryptContent(payload,CnCrypto.md5Upper(session.encryToken))
            val array=if(plain.trimStart().startsWith("[")) org.json.JSONArray(plain) else JSONObject(plain).getJSONArray("diLinkAutoInfoList")
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { Vehicle.fromJson(it) } }.distinctBy { it.vin }
        } catch(_:Exception) {throw ApiFailure(null,"车辆列表解析失败")}
    }
    fun listResponse(outer: JSONObject, session: Session): Int = vehiclesResponse(outer,session).size
    private fun controlBase(now: Long): LinkedHashMap<String,Any?> = linkedMapOf(
        "deviceName" to "XIAOMIPOCO F1", "deviceType" to "0", "imeiMD5" to config.imeiMd5,
        "mobileBrand" to "XIAOMI", "mobileModel" to "POCO F1", "networkOperator" to "无", "networkType" to "wifi",
        "osType" to "Android", "osVersion" to "35", "random" to nonce(), "softType" to "0", "timeStamp" to now.toString(), "version" to INNER_VERSION)
    private fun controlEnvelope(session: Session, vehicle: Vehicle, inner: LinkedHashMap<String,Any?>, now: Long): String {
        val type=if(vehicle.shared) 1 else 0
        val fields=LinkedHashMap(inner).apply { putAll(linkedMapOf("appChannel" to "99", "identifier" to session.identifier,
            "identifierType" to type,"imeiMD5" to config.imeiMd5,"reqTimestamp" to now.toString(),
            "targetBrand" to config.targetBrand,"vehicleBrand" to config.targetBrand,"objective" to vehicle.vin)) }
        val sign=CnCrypto.sign(fields.mapValues {it.value.toString()},CnCrypto.md5Upper(session.signToken))
        return WireJson.encode(outerDevice(linkedMapOf("appChannel" to "99",
            "encryData" to CnCrypto.encryptContent(WireJson.encode(inner),CnCrypto.md5Upper(session.encryToken)),
            "identifier" to session.identifier,"identifierType" to type,"imeiMD5" to config.imeiMd5,"objective" to vehicle.vin,
            "outModelTypes" to null,"reqTimestamp" to now.toString(),"sign" to sign,"softType" to null,
            "targetBrand" to config.targetBrand,"vehicleBrand" to config.targetBrand,"version" to null),now))
    }
    fun verifyPinRequest(session: Session, vehicle: Vehicle, pin: String): String {
        require(Regex("[0-9]{6}").matches(pin))
        val now=clock()
        val inner=linkedMapOf<String,Any?>("commandPwd" to CnCrypto.md5Upper(pin),"deviceType" to "0","functionType" to "remoteControl",
            "imeiMD5" to config.imeiMd5,"networkType" to "wifi","random" to nonce(),"timeStamp" to now.toString(),"version" to INNER_VERSION,"vin" to vehicle.vin)
        return controlEnvelope(session,vehicle,inner,now)
    }
    fun wakeRequest(session: Session, vehicle: Vehicle): String {
        val now=clock();return controlEnvelope(session,vehicle,controlBase(now).apply {put("vin",vehicle.vin)},now)
    }
    fun doorRequest(session: Session, vehicle: Vehicle, pin: String, action: DoorAction, requestSerial: String? = null): String {
        require(Regex("[0-9]{6}").matches(pin))
        val now=clock();val inner=controlBase(now).apply {
            putAll(linkedMapOf("vin" to vehicle.vin,"source" to "app","tboxVersion" to "3","autoType" to "1",
                "commandPwd" to CnCrypto.md5Upper(pin),"commandType" to action.command,"controlParamsMap" to "{}",
                "asyncControl" to "0","requestSerial" to (requestSerial ?: (now%100000).toString())))
        }
        return controlEnvelope(session,vehicle,inner,now)
    }
    fun telemetryRequest(session: Session,vehicle: Vehicle,requestSerial: String?=null): String {
        val now=clock();val inner=controlBase(now).apply {
            put("energyType","0");put("tboxVersion","3");put("vin",vehicle.vin)
            if(requestSerial!=null) put("requestSerial",requestSerial)
        }
        return controlEnvelope(session,vehicle,inner,now)
    }
    fun hvacStatusRequest(session: Session,vehicle: Vehicle): String {
        val now=clock();val inner=controlBase(now).apply {put("vin",vehicle.vin)}
        return controlEnvelope(session,vehicle,inner,now)
    }
    fun telemetryResponse(outer: JSONObject,session: Session): JSONObject {
        val code=outer.optString("code")
        if(code!="0" && code!="22") throw ApiFailure(code.takeIf {Regex("[0-9]{1,6}").matches(it)},
            when(code) {"1001" -> "该车辆不支持车辆数据接口，已隐藏";"240" -> "车辆数据读取被拒绝，账号权限或协议原因尚未确定";else -> "车辆数据读取失败"})
        val payload=outer.optString("respondData")
        if(payload.isBlank()) return JSONObject()
        return try {JSONObject(CnCrypto.decryptContent(payload,CnCrypto.md5Upper(session.encryToken)))}
            catch(_:Exception) {throw ApiFailure(null,"车辆数据解析失败")}
    }
    fun statusResponse(outer: JSONObject,session: Session): JSONObject {
        fun reject(value: JSONObject): Nothing {
            var message=loginFailureMessage(value)
            listOf(session.signToken,session.encryToken,session.userId,session.superId ?: "").filter {it.length>=6}.forEach {message=message.replace(it,"[已隐藏]")}
            throw ApiFailure(value.optString("code").takeIf {Regex("[0-9]{1,6}").matches(it)},"车辆状态读取失败："+message)
        }
        if(outer.optString("code") !in setOf("0","22")) reject(outer)
        if(outer.optString("respondData").isBlank()) return JSONObject()
        val data=try {JSONObject(CnCrypto.decryptContent(outer.getString("respondData"),CnCrypto.md5Upper(session.encryToken)))} catch(_:Exception) {throw ApiFailure(null,"车辆状态响应解析失败")}
        if(data.has("code") && data.optString("code") !in setOf("0","200","22")) reject(data)
        return data
    }
    fun controlResultRequest(session: Session,vehicle: Vehicle,serial: String,action: DoorAction): String {
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(serial))
        val now=clock()
        val inner=controlBase(now).apply {putAll(linkedMapOf("vin" to vehicle.vin,"requestSerial" to serial,"commandType" to action.command,"source" to "app","tboxVersion" to "3"))}
        return controlEnvelope(session,vehicle,inner,now)
    }
    fun rcAcknowledgement(outer: JSONObject,session: Session) {controlReceipt(outer,session)}
    fun controlReceipt(outer: JSONObject,session: Session): ControlReceipt {
        controlAcknowledgement(outer,true)
        val raw=outer.opt("respondData")
        if(raw==null || raw==JSONObject.NULL || raw.toString().isBlank()) return ControlReceipt(RemoteOutcome.UNKNOWN)
        val decoded=if(raw is JSONObject || raw is Number) raw else {
            val text=try {CnCrypto.decryptContent(raw.toString(),CnCrypto.md5Upper(session.encryToken)).trim()}
                catch(_:Exception) {throw ApiFailure(null,"车辆响应解析失败")}
            if(text.isBlank()) return ControlReceipt(RemoteOutcome.UNKNOWN)
            try {org.json.JSONTokener(text).nextValue()} catch(_:Exception) {text}
        }
        fun serial(value: Any?): String? = (if(value is String || value is Number) value.toString() else null)?.takeIf {
            it !in setOf("null","SUCCESS","success","OK","ok") && Regex("[A-Za-z0-9_-]{1,128}").matches(it)
        }
        if(decoded !is JSONObject) return ControlReceipt(RemoteOutcome.UNKNOWN,serial(decoded))
        var foundSerial: String?=null
        var foundOutcome=RemoteOutcome.UNKNOWN
        fun read(data: JSONObject,depth: Int) {
            if(depth>3) return
            if(data.has("code") && !data.isNull("code")) {
                val code=data.optString("code")
                if(code!="0" && code!="200") throw ApiFailure(code.takeIf {Regex("[0-9]{1,6}").matches(it)},"服务器拒绝车辆操作")
            }
            serial(data.opt("requestSerial"))?.let {foundSerial=it}
            val state=if(data.has("controlState")) when(data.optString("controlState")) {
                "0" -> RemoteOutcome.PENDING;"1" -> RemoteOutcome.SUCCESS;"2" -> RemoteOutcome.FAILURE;else -> RemoteOutcome.UNKNOWN
            } else when(data.optString("res")) {
                "1" -> RemoteOutcome.PENDING;"2" -> RemoteOutcome.SUCCESS
                else -> if(data.optString("res").toIntOrNull()?.let {it>2}==true) RemoteOutcome.FAILURE else RemoteOutcome.UNKNOWN
            }
            if(state!=RemoteOutcome.UNKNOWN) {
                if(foundOutcome!=RemoteOutcome.UNKNOWN && foundOutcome!=state) throw ApiFailure(null,"车辆结果状态不一致")
                foundOutcome=state
            }
            for(key in listOf("respondData","data")) {
                val child=data.opt(key)
                if(child is JSONObject) read(child,depth+1)
                else if(key=="respondData") serial(child)?.let {foundSerial=it}
            }
        }
        read(decoded,0)
        return ControlReceipt(foundOutcome,foundSerial)
    }
    fun controlAcknowledgement(outer: JSONObject, rc: Boolean = false) {
        val code=outer.optString("code")
        if(code=="0" || code=="22" || (rc && code=="200")) return
        val label=if(code in setOf("5005","5006","5011")) "操作 PIN 校验失败" else "服务器拒绝车辆操作"
        throw ApiFailure(code.takeIf { Regex("[0-9]{1,6}").matches(it) },label)
    }
}
