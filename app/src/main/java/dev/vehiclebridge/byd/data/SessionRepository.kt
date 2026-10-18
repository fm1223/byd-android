package dev.vehiclebridge.byd.data

import dev.vehiclebridge.byd.protocol.*
import dev.vehiclebridge.byd.bridge.*

class SavedAccount(val config: AccountConfig, val session: Session, val selectedVin: String? = null, val pins: Map<String,String> = emptyMap(), val binding: BemfaBinding? = null, val unsupportedTelemetry: Set<String> = emptySet(), val shutdownOnLock: Set<String> = emptySet()) {
    override fun toString()="SavedAccount([redacted])"
}
interface SessionStorage { fun read(): SavedAccount?; fun write(value: SavedAccount); fun clear() }
class SessionInfo(val vehicleCount: Int, val createdAt: Long, val restored: Boolean, val silentlyLoggedIn: Boolean,
    val vehicles: List<Vehicle> = emptyList(), val selectedVin: String? = null, val hasPin: Boolean = false, val binding: BemfaBinding? = null, val pinVins: Set<String> = emptySet(), val telemetry: Map<String,VehicleTelemetry> = emptyMap(), val unsupportedTelemetry: Set<String> = emptySet(), val shutdownOnLock: Set<String> = emptySet()) {
    var controlMessage: String? = null; internal set
    var selectedControlMessage: String? = null; internal set
    val selected get() = vehicles.find { it.vin==selectedVin }
}
/** Serialized by the controller. A vehicle command is never retried. */
class SessionRepository(private val api: AccountApi, private val storage: SessionStorage) {
    private val recentControls=mutableMapOf<String,String>()
    private var active: SavedAccount? = null
    private var available: List<Vehicle> = emptyList()
    private val telemetry=mutableMapOf<String,VehicleTelemetry>()
    private val unsupportedTelemetry=mutableSetOf<String>()
    private fun ready(saved: SavedAccount, vehicles: List<Vehicle>, restored: Boolean, silent: Boolean): SessionInfo {
        val selected=saved.selectedVin?.takeIf { vin -> vehicles.any {it.vin==vin} } ?: vehicles.singleOrNull()?.vin
        val pins=saved.pins.filterKeys {vin -> vehicles.any {it.vin==vin}}
        val binding=saved.binding?.let {value ->
            val devices=value.devices.map {device -> if(!pins.containsKey(device.vin) || vehicles.none {it.vin==device.vin}) device.enabled(false) else device}
            if(devices.zip(value.devices).any {(next,old) -> next.enabled!=old.enabled}) value.withDevices(devices) else value
        }
        val unsupported=saved.unsupportedTelemetry.filter {vin -> vehicles.any {it.vin==vin}}.toSet()
        unsupportedTelemetry.clear();unsupportedTelemetry.addAll(unsupported)
        val current=SavedAccount(saved.config,saved.session,selected,pins,binding,unsupported,saved.shutdownOnLock)
        if(selected!=saved.selectedVin || current.pins!=saved.pins || current.binding?.id!=saved.binding?.id || current.binding?.enabled!=saved.binding?.enabled || unsupported!=saved.unsupportedTelemetry) storage.write(current)
        telemetry.keys.retainAll(vehicles.map {it.vin}.toSet())
        recentControls.keys.retainAll(vehicles.map {it.vin}.toSet())
        unsupportedTelemetry.retainAll(vehicles.map {it.vin}.toSet())
        active=current;available=vehicles
        return info(restored,silent)
    }
    private fun info(restored: Boolean=true, silent: Boolean=false): SessionInfo {
        val saved=active ?: throw ApiFailure(null,"请先登录")
        return SessionInfo(available.size,saved.session.createdAt,restored,silent,available,saved.selectedVin,saved.pins.containsKey(saved.selectedVin),saved.binding,saved.pins.keys,telemetry.toMap(),unsupportedTelemetry.toSet(),saved.shutdownOnLock).also {it.selectedControlMessage=recentControls[saved.selectedVin]}
    }
    @Synchronized fun login(config: AccountConfig): SessionInfo {
        telemetry.clear();unsupportedTelemetry.clear();recentControls.clear()
        val session=api.login(config);val vehicles=api.vehicles(config,session)
        val saved=SavedAccount(config,session,vehicles.singleOrNull()?.vin)
        storage.write(saved)
        return ready(saved,vehicles,false,false)
    }
    @Synchronized fun restore(): SessionInfo? {
        val saved=storage.read() ?: return null.also {active=null;available=emptyList();telemetry.clear();unsupportedTelemetry.clear();recentControls.clear()}
        try {
            return ready(saved,api.vehicles(saved.config,saved.session),true,false)
        } catch(e:ApiFailure) {
            if(!e.sessionExpired) throw e
            val replacement=api.login(saved.config)
            val vehicles=api.vehicles(saved.config,replacement)
            val updated=SavedAccount(saved.config,replacement,saved.selectedVin,saved.pins,saved.binding,unsupportedTelemetry=saved.unsupportedTelemetry,shutdownOnLock=saved.shutdownOnLock)
            storage.write(updated)
            return ready(updated,vehicles,false,true)
        }
    }
    @Synchronized fun select(vin: String): SessionInfo {
        val saved=active ?: throw ApiFailure(null,"请先登录")
        require(available.any {it.vin==vin})
        val changed=SavedAccount(saved.config,saved.session,vin,saved.pins,saved.binding,unsupportedTelemetry=saved.unsupportedTelemetry,shutdownOnLock=saved.shutdownOnLock)
        storage.write(changed);active=changed;return info()
    }
    private fun refreshed(vin: String): Pair<SavedAccount,Vehicle> {
        restore() ?: throw ApiFailure(null,"请先登录")
        val saved=active ?: throw ApiFailure(null,"请先登录")
        val vehicle=available.find {it.vin==vin} ?: throw ApiFailure(null,"车辆已不在账号列表中")
        if(saved.selectedVin!=vin) throw ApiFailure(null,"车辆选择已改变，请重新操作")
        if(!Regex("[A-HJ-NPR-Z0-9]{17}").matches(vin)) throw ApiFailure(null,"车辆 VIN 无效")
        return saved to vehicle
    }
    @Synchronized fun refreshTelemetry(vin: String): SessionInfo {
        if(vin in unsupportedTelemetry) throw ApiFailure("1001","该车辆不支持车辆数据接口，已隐藏")
        val (saved,vehicle)=refreshed(vin)
        val report=try {api.telemetry(saved.config,saved.session,vehicle)} catch(e: ApiFailure) {
            // Only the explicit unsupported code disables this optional feature.
            if(e.code=="1001") {
                unsupportedTelemetry.add(vin);telemetry.remove(vin)
                val changed=SavedAccount(saved.config,saved.session,saved.selectedVin,saved.pins,saved.binding,unsupportedTelemetry.toSet(),saved.shutdownOnLock)
                storage.write(changed);active=changed
            }
            throw e
        }
        if(!report.hasData) throw ApiFailure(null,"车辆尚未返回有效数据，请稍后手动刷新")
        telemetry[vin]=report
        return info()
    }
    @Synchronized fun savePin(vin: String, pin: String): SessionInfo {
        if(!Regex("[0-9]{6}").matches(pin)) throw ApiFailure(null,"PIN 必须为六位数字")
        val (saved,vehicle)=refreshed(vin)
        api.verifyPin(saved.config,saved.session,vehicle,pin)
        val changed=SavedAccount(saved.config,saved.session,saved.selectedVin,saved.pins + (vin to pin),saved.binding,unsupportedTelemetry=saved.unsupportedTelemetry,shutdownOnLock=saved.shutdownOnLock)
        storage.write(changed);active=changed;return info()
    }
    @Synchronized fun removePin(vin: String): SessionInfo {
        val saved=active ?: throw ApiFailure(null,"请先登录")
        val changed=SavedAccount(saved.config,saved.session,saved.selectedVin,saved.pins-vin,saved.binding?.let {value -> value.withDevices(value.devices.map {if(it.vin==vin) it.enabled(false) else it})},unsupportedTelemetry=saved.unsupportedTelemetry,shutdownOnLock=saved.shutdownOnLock)
        storage.write(changed);active=changed;return info()
    }
    @Synchronized fun sendDoor(vin: String, action: DoorAction, temporaryPin: String? = null, progress: (String)->Unit = {}): SessionInfo {
        val (saved,vehicle)=refreshed(vin)
        val pin=temporaryPin ?: saved.pins[vin] ?: throw ApiFailure(null,"请先输入操作 PIN")
        if(!Regex("[0-9]{6}").matches(pin)) throw ApiFailure(null,"PIN 必须为六位数字")
        api.verifyPin(saved.config,saved.session,vehicle,pin)
        return sendCommands(saved,vehicle,pin,action,progress)
    }
    @Synchronized fun setShutdownOnLock(vin: String, enabled: Boolean): SessionInfo {
        val saved=active ?: throw ApiFailure(null,"请先登录")
        require(available.any {it.vin==vin})
        val values=if(enabled) saved.shutdownOnLock+vin else saved.shutdownOnLock-vin
        val changed=SavedAccount(saved.config,saved.session,saved.selectedVin,saved.pins,saved.binding,saved.unsupportedTelemetry,values)
        storage.write(changed);active=changed;return info()
    }
    private fun sendCommands(saved: SavedAccount,vehicle: Vehicle,pin: String,action: DoorAction,progress: (String)->Unit): SessionInfo {
        try {
        val combined=action==DoorAction.LOCK && vehicle.vin in saved.shutdownOnLock
        val firstAction=if(combined) DoorAction.SHUTDOWN else action
        val first=api.executeControl(saved.config,saved.session,vehicle,pin,firstAction,progress)
        var second: ControlReceipt?=null
        if(combined && first.outcome!=RemoteOutcome.FAILURE) {
            progress(first.describe(firstAction)+"\n锁车：正在发送请求")
            try {second=api.executeControl(saved.config,saved.session,vehicle,pin,DoorAction.LOCK) {progress(first.describe(firstAction)+"\n"+it)}}
            catch(e: Exception) {
                throw ApiFailure((e as? ApiFailure)?.code,first.describe(firstAction)+"；锁车未完成或结果未知："+((e as? ApiFailure)?.label ?: "请求异常")+"。未自动重试")
            }
        }
        val message=first.describe(firstAction)+(if(second!=null) "\n"+second!!.describe(DoorAction.LOCK) else if(combined) "\n锁车：未发送（熄火执行失败）" else "")
        recentControls[vehicle.vin]=message
        return info().also {it.controlMessage=message}
        } catch(e: Exception) {
            recentControls[vehicle.vin]=(e as? ApiFailure)?.let {it.label+(it.code?.let {code -> "（代码 $code）"} ?: "")} ?: "操作结果未知；未自动重试"
            throw e
        }
    }
    @Synchronized fun saveBinding(binding: BemfaBinding): SessionInfo {
        restore() ?: throw ApiFailure(null,"请先登录")
        val saved=active ?: throw ApiFailure(null,"请先登录")
        if(binding.devices.any {device -> !saved.pins.containsKey(device.vin) || available.none {it.vin==device.vin}}) throw ApiFailure(null,"请先为绑定车辆验证并保存 PIN")
        val changed=SavedAccount(saved.config,saved.session,saved.selectedVin,saved.pins,binding.enabled(false),unsupportedTelemetry=saved.unsupportedTelemetry,shutdownOnLock=saved.shutdownOnLock)
        storage.write(changed);active=changed;return info()
    }
    @Synchronized fun saveDevice(key: String,device: BemfaDevice,replacingId: String?=null): SessionInfo {
        restore() ?: throw ApiFailure(null,"请先登录")
        val saved=active ?: throw ApiFailure(null,"请先登录")
        if(!saved.pins.containsKey(device.vin) || available.none {it.vin==device.vin}) throw ApiFailure(null,"请先为绑定车辆验证并保存 PIN")
        val old=saved.binding
        if(replacingId!=null && old?.devices?.none {it.id==replacingId}!=false) throw ApiFailure(null,"设备已改变，请重新选择")
        val devices=(old?.devices ?: emptyList()).filterNot {it.id==replacingId}+device
        if(devices.any {it.id!=device.id && it.topic==device.topic}) throw ApiFailure(null,"该 topic 已绑定其他设备，请使用独立 topic")
        if(devices.size>20) throw ApiFailure(null,"最多支持 20 个设备")
        val value=BemfaBinding(key,devices.first().topic,devices.first().vin,old?.enabled==true,devices=devices)
        return replaceBinding(saved,value)
    }
    private fun replaceBinding(saved: SavedAccount,value: BemfaBinding?): SessionInfo {
        val changed=SavedAccount(saved.config,saved.session,saved.selectedVin,saved.pins,value,saved.unsupportedTelemetry,saved.shutdownOnLock)
        storage.write(changed);active=changed;return info()
    }
    @Synchronized fun removeDevice(id: String): SessionInfo {
        val saved=active ?: throw ApiFailure(null,"请先登录")
        val old=saved.binding ?: throw ApiFailure(null,"尚未配置设备")
        val devices=old.devices.filterNot {it.id==id}
        return replaceBinding(saved,if(devices.isEmpty()) null else old.withDevices(devices))
    }
    @Synchronized fun setDeviceEnabled(id: String,enabled: Boolean): SessionInfo {
        val saved=active ?: throw ApiFailure(null,"请先登录")
        val old=saved.binding ?: throw ApiFailure(null,"尚未配置设备")
        val device=old.devices.find {it.id==id} ?: throw ApiFailure(null,"设备已改变")
        if(enabled && (!saved.pins.containsKey(device.vin) || available.none {it.vin==device.vin})) throw ApiFailure(null,"请先为设备车辆验证并保存 PIN")
        return replaceBinding(saved,old.withDevices(old.devices.map {if(it.id==id) it.enabled(enabled) else it}))
    }
    @Synchronized fun enableBinding(): SessionInfo {
        restore() ?: throw ApiFailure(null,"请先登录")
        val saved=active ?: throw ApiFailure(null,"请先登录")
        val binding=saved.binding ?: throw ApiFailure(null,"请先保存巴法云绑定")
        if(binding.activeDevices.isEmpty() || binding.activeDevices.any {device -> !saved.pins.containsKey(device.vin) || available.none {it.vin==device.vin}}) throw ApiFailure(null,"绑定车辆没有已保存的 PIN")
        val changed=SavedAccount(saved.config,saved.session,saved.selectedVin,saved.pins,binding.enabled(true),unsupportedTelemetry=saved.unsupportedTelemetry,shutdownOnLock=saved.shutdownOnLock)
        storage.write(changed);active=changed;return info()
    }
    @Synchronized fun disableBinding(): SessionInfo? {
        val saved=storage.read() ?: return null
        val changed=SavedAccount(saved.config,saved.session,saved.selectedVin,saved.pins,saved.binding?.enabled(false),unsupportedTelemetry=saved.unsupportedTelemetry,shutdownOnLock=saved.shutdownOnLock)
        storage.write(changed);active=changed
        return info()
    }
    @Synchronized fun sendBoundDoor(bindingId: String, action: DoorAction, progress: (String)->Unit = {},configurationId: String?=null): SessionInfo {
        restore() ?: throw ApiFailure(null,"请先登录")
        val saved=active ?: throw ApiFailure(null,"请先登录")
        val binding=saved.binding?.takeIf {it.enabled && (configurationId==null || it.id==configurationId)} ?: throw ApiFailure(null,"绑定已停止或改变，本条指令未执行")
        val device=binding.activeDevices.find {it.id==bindingId} ?: throw ApiFailure(null,"设备已停止或改变，本条指令未执行")
        if(!device.function.allows(action)) throw ApiFailure(null,"指令与设备功能不匹配，未执行")
        val vehicle=available.find {it.vin==device.vin} ?: throw ApiFailure(null,"绑定车辆不在账号列表中")
        val pin=saved.pins[device.vin] ?: throw ApiFailure(null,"绑定车辆没有已保存的 PIN")
        api.verifyPin(saved.config,saved.session,vehicle,pin)
        return sendCommands(saved,vehicle,pin,action,progress)
    }
    @Synchronized fun snapshot(): SessionInfo? = active?.let {info()}
    @Synchronized fun logout() {storage.clear();active=null;available=emptyList();telemetry.clear();unsupportedTelemetry.clear()}
}
