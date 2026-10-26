package dev.vehiclebridge.byd.bridge

import dev.vehiclebridge.byd.protocol.DoorAction
import java.util.UUID

/** Fixed pairs keep on/off semantics visible when a device is configured. */
enum class BemfaFunction(val label: String,val onAction: DoorAction,val offAction: DoorAction,val hint: String) {
    DOOR("车锁",DoorAction.UNLOCK,DoorAction.LOCK,"on 解锁 · off 锁车（按车辆设置先熄火）"),
    TRUNK("后备箱",DoorAction.OPEN_TRUNK,DoorAction.CLOSE_TRUNK,"on 打开后备箱 · off 关闭后备箱"),
    CLIMATE("空调",DoorAction.CLIMATE_ON,DoorAction.CLIMATE_OFF,"on 开启空调（默认参数，10分钟）· off 关闭空调，可能同时下电；兼容带 # 参数消息，暂只处理开关");
    fun allows(action: DoorAction)=action==onAction || action==offAction
}
class BemfaDevice(val topic: String,val vin: String,val function: BemfaFunction=BemfaFunction.DOOR,
    val name: String=function.label,val enabled: Boolean=true,val id: String=UUID.randomUUID().toString()) {
    init {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(topic))
        require(Regex("[A-HJ-NPR-Z0-9]{17}").matches(vin))
        require(name.isNotBlank() && name.length<=40)
        require(id.isNotBlank() && id.length<=64)
    }
    fun enabled(value: Boolean)=BemfaDevice(topic,vin,function,name,value,id)
    override fun toString()="BemfaDevice([redacted])"
}
/** One account connection subscribes all enabled devices; legacy fields support old records. */
class BemfaBinding(val key: String,val topic: String,val vin: String,val enabled: Boolean=false,
    val id: String=UUID.randomUUID().toString(),devices: List<BemfaDevice>?=null) {
    val devices: List<BemfaDevice> = (devices ?: listOf(BemfaDevice(topic,vin,id=id))).toList()
    init {
        require(Regex("[A-Fa-f0-9]{32}").matches(key))
        require(id.isNotBlank() && id.length<=64)
        require(this.devices.size in 1..20)
        require(this.devices.map {it.topic}.distinct().size==this.devices.size) {"topic 不能重复"}
        require(this.devices.map {it.id}.distinct().size==this.devices.size)
    }
    val activeDevices get()=devices.filter {it.enabled}
    fun enabled(value: Boolean)=BemfaBinding(key,topic,vin,value,id,devices)
    fun withDevices(values: List<BemfaDevice>,running: Boolean=enabled): BemfaBinding {
        require(values.isNotEmpty())
        return BemfaBinding(key,values.first().topic,values.first().vin,running && values.any {it.enabled},devices=values)
    }
    override fun toString()="BemfaBinding([redacted])"
    companion object {const val SERVER_URI="tcp://mqtt.bemfa.com:9501"}
}
