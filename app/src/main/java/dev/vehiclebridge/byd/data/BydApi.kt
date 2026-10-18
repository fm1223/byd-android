package dev.vehiclebridge.byd.data

import dev.vehiclebridge.byd.protocol.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface AccountApi {
    fun login(config: AccountConfig): Session
    fun vehicleCount(config: AccountConfig, session: Session): Int
    fun vehicles(config: AccountConfig, session: Session): List<Vehicle> = throw ApiFailure(null,"车辆列表不可用")
    fun verifyPin(config: AccountConfig, session: Session, vehicle: Vehicle, pin: String): Unit = throw ApiFailure(null,"PIN 校验不可用")
    fun telemetry(config: AccountConfig,session: Session,vehicle: Vehicle): VehicleTelemetry = throw ApiFailure(null,"车辆数据不可用")
    fun executeControl(config: AccountConfig,session: Session,vehicle: Vehicle,pin: String,action: DoorAction,progress: (String)->Unit = {}): ControlReceipt {
        sendDoor(config,session,vehicle,pin,action)
        return ControlReceipt(RemoteOutcome.ACCEPTED)
    }
    fun sendDoor(config: AccountConfig, session: Session, vehicle: Vehicle, pin: String, action: DoorAction): Unit = throw ApiFailure(null,"车辆操作不可用")
}
class BydApi(private val pause: (Long)->Unit = {Thread.sleep(it)}, private val transport: ((String, String) -> JSONObject)? = null) : AccountApi {
    private val cookies = mutableListOf<Cookie>()
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS)
        .cookieJar(object: CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { synchronized(this@BydApi.cookies) { cookies.forEach { c -> this@BydApi.cookies.removeAll {it.name==c.name && it.domain==c.domain && it.path==c.path}; this@BydApi.cookies.add(c) } } }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) { cookies.removeAll {it.expiresAt<System.currentTimeMillis()};cookies.filter {it.matches(url)} }
        }).build()
    private fun post(endpoint: String, outer: String): JSONObject {
        transport?.let { return it(endpoint,outer) }
        val request=try {
            Request.Builder().url(CnProtocol.BASE_URL+endpoint).header("Accept-Encoding","identity")
                .header("User-Agent","okhttp/4.12.0").header("version",CnProtocol.INNER_VERSION).header("platform","ANDROID").header("BrandFlag","dynasty")
                .post(WireJson.encode(linkedMapOf("request" to Wbsk.encrypt(outer))).toRequestBody("application/json; charset=UTF-8".toMediaType())).build()
        } catch(_:Exception) {throw ApiFailure(null,"请求封装失败")}
        return try {
            http.newCall(request).execute().use { response ->
                if(!response.isSuccessful) throw ApiFailure(null,"HTTP ${response.code}")
                val body=response.body ?: throw ApiFailure(null,"服务器响应为空")
                // Bound untrusted bodies without logging raw data.
                val source=body.source();if(source.request(4_000_001) && source.buffer.size>4_000_000) throw ApiFailure(null,"服务器响应过大")
                JSONObject(Wbsk.decrypt(JSONObject(body.string()).getString("response")))
            }
        } catch(e:ApiFailure) {throw e} catch(_:java.io.IOException) {throw ApiFailure(null,"网络请求失败，请检查连接")} catch(_:Exception) {throw ApiFailure(null,"响应解析失败")}
    }
    override fun login(config: AccountConfig): Session {
        val protocol=CnProtocol(config)
        return protocol.loginResponse(post("/app/auth/login",protocol.loginRequest()))
    }
    override fun vehicleCount(config: AccountConfig, session: Session): Int = vehicles(config,session).size
    override fun vehicles(config: AccountConfig, session: Session): List<Vehicle> {
        val protocol=CnProtocol(config)
        return protocol.vehiclesResponse(post("/app/auth/getAllListByUserId",protocol.listRequest(session)),session)
    }
    override fun verifyPin(config: AccountConfig, session: Session, vehicle: Vehicle, pin: String) {
        val protocol=CnProtocol(config)
        protocol.controlAcknowledgement(post("/vehicle/vehicleswitch/verifyControlPassword",protocol.verifyPinRequest(session,vehicle,pin)))
    }
    override fun sendDoor(config: AccountConfig, session: Session, vehicle: Vehicle, pin: String, action: DoorAction) {
        dispatch(config,session,vehicle,pin,action,null)
    }
    private fun dispatch(config: AccountConfig,session: Session,vehicle: Vehicle,pin: String,action: DoorAction,serial: String?): ControlReceipt {
        val protocol=CnProtocol(config)
        try {
            protocol.rcAcknowledgement(post("/control/rc/remoteAwakeRequest",protocol.wakeRequest(session,vehicle)),session)
        } catch(e: ApiFailure) {
            throw ApiFailure(e.code,"车辆唤醒失败；控制命令未发送："+e.label)
        }
        try {
            return protocol.controlReceipt(post("/control/rc/remoteControl",protocol.doorRequest(session,vehicle,pin,action,serial)),session)
        } catch(e: ApiFailure) {
            val operation=when(action) {DoorAction.LOCK -> "锁车";DoorAction.UNLOCK -> "解锁";DoorAction.SHUTDOWN -> "熄火";DoorAction.OPEN_TRUNK -> "打开后备箱";DoorAction.CLOSE_TRUNK -> "关闭后备箱"}
            if(e.code!=null) throw ApiFailure(e.code,operation+"请求被服务器拒绝："+e.label)
            throw ApiFailure(null,operation+"请求结果未知，请检查车辆；未自动重试")
        }
    }
    override fun executeControl(config: AccountConfig,session: Session,vehicle: Vehicle,pin: String,action: DoorAction,progress: (String)->Unit): ControlReceipt {
        val sentSerial=(System.currentTimeMillis()%100000).toString()
        val first=dispatch(config,session,vehicle,pin,action,sentSerial)
        if(first.outcome in setOf(RemoteOutcome.SUCCESS,RemoteOutcome.FAILURE)) return first
        val serial=first.serial ?: sentSerial
        val protocol=CnProtocol(config)
        progress(ControlReceipt(RemoteOutcome.ACCEPTED).describe(action)+"，正在查询结果")
        repeat(5) {
            val result=try {
                pause(1500)
                protocol.controlReceipt(post("/control/rc/remoteControlResult",protocol.controlResultRequest(session,vehicle,serial,action)),session)
            } catch(e: Exception) {
                val code=(e as? ApiFailure)?.code
                return ControlReceipt(RemoteOutcome.UNKNOWN,detail=if(code!=null) "结果查询失败，代码 $code" else "结果查询未完成；未重发操作")
            }
            if(result.serial!=null && result.serial!=serial) return ControlReceipt(RemoteOutcome.UNKNOWN,detail="结果编号不匹配")
            if(result.outcome in setOf(RemoteOutcome.SUCCESS,RemoteOutcome.FAILURE)) return result
            progress(ControlReceipt(if(result.outcome==RemoteOutcome.PENDING) RemoteOutcome.PENDING else RemoteOutcome.ACCEPTED).describe(action)+"，等待服务器反馈")
        }
        return ControlReceipt(RemoteOutcome.UNKNOWN,detail="未在查询次数内获得最终结果；未重发操作")
    }
    override fun telemetry(config: AccountConfig,session: Session,vehicle: Vehicle): VehicleTelemetry {
        val protocol=CnProtocol(config)
        val first=protocol.telemetryResponse(post("/vehicleInfo/vehicle/vehicleRealTimeRequest",protocol.telemetryRequest(session,vehicle)),session)
        val initial=VehicleTelemetry.fromJson(first)
        val serial=first.optString("requestSerial").takeIf {it.isNotBlank() && it!="null" && it.length<=100}
        if(initial.hasData || serial==null) return initial
        // One result query only. No wake, door command, PIN or automatic retry.
        Thread.sleep(1200)
        return VehicleTelemetry.fromJson(protocol.telemetryResponse(post("/vehicleInfo/vehicle/vehicleRealTimeResult",protocol.telemetryRequest(session,vehicle,serial)),session))
    }
    fun clearCookies() { synchronized(cookies) {cookies.clear()} }
}
