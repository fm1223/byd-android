package dev.vehiclebridge.byd

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.vehiclebridge.byd.data.*
import dev.vehiclebridge.byd.protocol.*
import dev.vehiclebridge.byd.bridge.BemfaBinding
import java.util.concurrent.Executors

class AuthState(val busy: Boolean=false, val info: SessionInfo?=null, val message: String="请输入账号密码登录", val action: DoorAction?=null, val actionVin: String?=null, val refreshing: Boolean=false, val climates: Map<String,ClimateReport> = emptyMap(), val climateBusy: Set<String> = emptySet())
class AuthController private constructor(context: Context) {
    companion object {
        @Volatile private var instance: AuthController?=null
        fun get(context: Context): AuthController = instance ?: synchronized(this) {instance ?: AuthController(context.applicationContext).also {instance=it}}
    }
    private val appContext=context.applicationContext
    private val api=BydApi()
    private val repository=SessionRepository(api,AndroidSessionStore.get(context),ClimateTemperaturePreferences(context)::get)
    private val executor=Executors.newSingleThreadExecutor()
    private val climateExecutor=Executors.newSingleThreadExecutor()
    private val climates=mutableMapOf<String,ClimateReport>()
    private val climateBusy=mutableSetOf<String>()
    private val climateTickets=mutableMapOf<String,Long>()
    private val climateRefreshed=mutableMapOf<String,Long>()
    private var climateEpoch=0L
    private val main=Handler(Looper.getMainLooper())
    private val observers=mutableSetOf<(AuthState)->Unit>()
    var state=AuthState();private set
    private val lastStatusRefresh=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private var initialized=false
    private var pending=0
    private var activeAction: DoorAction?=null
    private var activeVin: String?=null
    private var refreshing=false
    private var afterRefresh: (()->Unit)?=null
    fun observe(observer: (AuthState)->Unit) {observers.add(observer);observer(state)}
    fun remove(observer: (AuthState)->Unit) {observers.remove(observer)}
    private fun update(value: AuthState) {
        if(state.info?.createdAt!=value.info?.createdAt || value.info==null) {climateEpoch++;climates.clear();climateBusy.clear();climateTickets.clear();climateRefreshed.clear()}
        state=AuthState(value.busy,value.info,value.message,activeAction,activeVin,refreshing,climates.toMap(),climateBusy.toSet());observers.toList().forEach {it(state)}}
    private fun finished() {activeAction=null;activeVin=null;refreshing=false;val next=afterRefresh;afterRefresh=null;next?.invoke()}
    private fun run(message: String, successMessage: String? = null, complete: ((String)->Unit)? = null, action: ()->SessionInfo?) {
        if(pending>0) {complete?.invoke("操作进行中，本条指令未执行");return}
        pending++
        val previous=state.info
        update(AuthState(true,previous,message))
        executor.execute {
            try {
                val result=action()
                main.post {pending--;update(AuthState(pending>0,result,result?.controlMessage ?: successMessage ?: when {result==null -> "请输入账号密码登录";result.silentlyLoggedIn -> "会话已通过静默登录恢复";result.restored -> "已复用保存的会话";else -> "登录成功，信息已加密保存"}));complete?.invoke(result?.controlMessage ?: successMessage ?: "已完成");finished()}
            } catch(e:Exception) {
                val failure=if(e is ApiFailure) e.label + (e.code?.let {"（代码 $it）"} ?: "") else "操作未完成，请重试"
                val latest=repository.snapshot() ?: previous
                main.post {pending--;update(AuthState(pending>0,latest,failure));complete?.invoke(failure);finished()}
            }
        }
    }
    fun initialize() {if(!initialized){initialized=true;run("正在恢复登录信息",action=repository::restore)}}
    fun login(config: AccountConfig) = run("正在登录并验证车辆列表") {repository.login(config)}
    fun check() = run("正在验证保存的会话",action=repository::restore)
    fun select(vin: String) = run("正在选择车辆","已选择车辆") {repository.select(vin)}
    fun savePin(vin: String,pin: String) = run("正在验证操作 PIN","PIN 验证通过，已加密保存") {repository.savePin(vin,pin)}
    fun removePin(vin: String) = run("正在移除 PIN","已移除保存的 PIN") {repository.removePin(vin)}
    fun refreshClimateStatus(vin: String,automatic: Boolean=true) {
        val info=state.info ?: return
        if(info.selectedVin!=vin || !info.vehicleStatus.canReadClimate()) {
            climateTickets[vin]=(climateTickets[vin] ?: 0L)+1
            climates.remove(vin);climateBusy.remove(vin);climateRefreshed.remove(vin)
            update(AuthState(state.busy,state.info,state.message))
            return
        }
        val now=android.os.SystemClock.elapsedRealtime()
        if(automatic && (vin in climateBusy || climateRefreshed[vin]?.let {now-it in 0 until 60_000L}==true)) return
        val ticket=(climateTickets[vin] ?: 0L)+1
        climateTickets[vin]=ticket;climateBusy.add(vin)
        val epoch=climateEpoch
        update(AuthState(state.busy,state.info,state.message))
        climateExecutor.execute {
            val report=try {repository.fetchClimateStatus(vin)} catch(e: Exception) {ClimateReport(error=(e as? ApiFailure)?.label ?: "空调状态读取失败")}
            main.post {
                if(climateEpoch==epoch && climateTickets[vin]==ticket) {
                    if(state.info?.selectedVin==vin && state.info?.vehicleStatus.canReadClimate()) climates[vin]=report else climates.remove(vin)
                    climateBusy.remove(vin)
                    if(report.running!=null && report.targetCelsius!=null) climateRefreshed[vin]=android.os.SystemClock.elapsedRealtime() else climateRefreshed.remove(vin)
                    update(AuthState(state.busy,state.info,state.message))
                }
            }
        }
    }
    fun refreshVehicleStatus(vin: String,automatic: Boolean=false) {
        if(pending>0) return
        val last=lastStatusRefresh[vin]
        if(automatic && state.info?.vehicleStatus!=null && last!=null && android.os.SystemClock.elapsedRealtime()-last in 0 until 60_000L) {refreshClimateStatus(vin,automatic);return}
        refreshing=true
        run("正在刷新车辆状态","车辆状态查询完成",complete={lastStatusRefresh[vin]=android.os.SystemClock.elapsedRealtime();refreshClimateStatus(vin,automatic)}) {repository.refreshVehicleStatus(vin)}
    }
    private fun controlled(vin: String,action: ()->SessionInfo): SessionInfo {
        val requestedAt=System.currentTimeMillis()
        var result: SessionInfo?=null
        try {result=action()}
        finally {controlProgress("正在更新车辆状态，等待最新上报");repository.refreshAfterControl(vin,requestedAt);lastStatusRefresh[vin]=android.os.SystemClock.elapsedRealtime()}
        return repository.snapshot()!!.also {it.controlMessage=result?.controlMessage}
    }
    fun refreshTelemetry(vin: String) = run("正在刷新车辆数据","车辆数据已更新") {repository.refreshTelemetry(vin)}
    fun setShutdownOnLock(vin: String,enabled: Boolean) = run("正在保存锁车偏好","锁车偏好已保存") {repository.setShutdownOnLock(vin,enabled)}
    private fun controlProgress(text: String) {main.post {if(pending>0) update(AuthState(true,state.info,text))}}
    fun sendDoor(vin: String,action: DoorAction,pin: String?=null,temperatureCelsius: Int?=null) {
        if(pending>0) return
        activeAction=action;activeVin=vin
        run("正在发送车辆请求","请求已发送，车辆实际状态请现场确认",complete={dev.vehiclebridge.byd.bridge.BridgeNotifications.result(appContext,it);refreshClimateStatus(vin,false)}) {controlled(vin) {repository.sendDoor(vin,action,pin,::controlProgress,temperatureCelsius)}}
    }
    fun saveBinding(binding: BemfaBinding) = run("正在保存设备绑定","绑定已保存，点击启动后台联动") {repository.saveBinding(binding)}
    fun saveDevice(key: String,device: dev.vehiclebridge.byd.bridge.BemfaDevice,replacingId: String?) = run("正在保存设备","设备已保存") {repository.saveDevice(key,device,replacingId)}
    fun removeDevice(id: String) = run("正在删除设备","设备已删除") {repository.removeDevice(id)}
    fun setDeviceEnabled(id: String,enabled: Boolean) = run("正在更新设备","设备已更新") {repository.setDeviceEnabled(id,enabled)}
    fun enableBinding() = run("正在启动后台联动","后台联动已启用") {repository.enableBinding()}
    fun dispatchBridge(id: String,action: DoorAction,configurationId: String?=null,temperatureCelsius: Int?=null,complete: (String)->Unit) {
        // Keep one fresh delivery during a read-only refresh, never queue behind vehicle controls.
        if(pending>0 && refreshing && afterRefresh==null) {
            val received=android.os.SystemClock.elapsedRealtime()
            afterRefresh={if(android.os.SystemClock.elapsedRealtime()-received<=30_000) dispatchBridge(id,action,configurationId,temperatureCelsius,complete) else complete("等待状态刷新超时，本条指令未执行，请重新发送")}
            return
        }
        if(pending>0) {complete("操作进行中，本条指令未执行，请稍后重新发送");return}
        val vin=state.info?.binding?.activeDevices?.find {it.id==id}?.vin
        activeAction=action;activeVin=vin
        run("正在执行巴法云指令","请求已发送，车辆实际状态请现场确认",{text -> dev.vehiclebridge.byd.bridge.BridgeNotifications.result(appContext,text);if(vin!=null) refreshClimateStatus(vin,false);complete(text)}) {
            if(vin==null) throw ApiFailure(null,"设备绑定已改变，本条指令未执行")
            controlled(vin) {repository.sendBoundDoor(id,action,::controlProgress,configurationId,temperatureCelsius)}
        }
    }
    fun stopBridge() {
        pending++
        update(AuthState(true,state.info,"正在停止后台联动"))
        executor.execute {
            try {
                val info=repository.disableBinding()
                main.post {pending--;update(AuthState(pending>0,info,"后台联动已停止"))}
            } catch(_:Exception) { main.post {pending--;update(AuthState(pending>0,state.info,"停止设置保存失败，请重试"))} }
        }
    }
    fun logout() = run("正在退出登录") {repository.logout();api.clearCookies();null}
}
