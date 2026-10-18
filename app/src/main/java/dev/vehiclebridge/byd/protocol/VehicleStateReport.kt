package dev.vehiclebridge.byd.protocol

import org.json.JSONObject
import java.text.DateFormat
import java.util.Base64
import java.util.Date

/** Keeps vehicle and HVAC reports separate; neither request time nor A/C state proves engine shutdown. */
class VehicleStateReport(data: JSONObject) {
    private val body=data.optJSONObject("vehicleInfo") ?: data
    val locks=VehicleLockReport(data)
    private fun number(key: String)=body.opt(key)?.takeIf {it !is Boolean}?.toString()?.toIntOrNull()
    val onlineState=(number("onlineState") ?: data.opt("onlineState")?.takeIf {it !is Boolean}?.toString()?.toIntOrNull())?.takeIf {it in 1..2}
    val connectState=(number("connectState") ?: data.opt("connectState")?.takeIf {it !is Boolean}?.toString()?.toIntOrNull())?.takeIf {it in 0..1}
    val trunk=number("trunkLid") ?: number("backCover")
    val power=number("powerGear")
    fun hasControlStates()=locks.locks.all {it!=null} && trunk in listOf(0,1) && power in listOf(1,3)
    fun completeAndRecent(now: Long)=hasControlStates() && locks.recent(now)
    fun describe(): String {
        val rear=when(trunk) {0->"关闭（0，关闭到位及锁止仍需核对）";1->"打开（1）";else->"未知／未提供"}
        val supply=when(power) {1->"下电（1，待实车核对）";3->"上电（3，待实车核对）";else->"未知／未提供"}
        val vehicle=number("vehicleState")?.let {"\nvehicleState：$it（含义待验证）"} ?: ""
        return "【车锁】\n"+locks.describe()+"\n\n【后备箱】\n最近上报：$rear\n使用同一车辆上报时间；开合不等于锁止。\n\n【熄火参考：车辆电源】\n电源档位：$supply$vehicle\n电源与发动机状态不能直接画等号；过期数据不能确认当前状态。"
    }
    companion object {
        fun hvacDescription(data: JSONObject): String {
            var body=data.optJSONObject("statusNow") ?: data.optJSONObject("data") ?: data
            val encoded=body.optString("outputBase64")
            if(encoded.isNotBlank()) {
                if(encoded.length>65536) throw ApiFailure(null,"空调响应过大")
                body=try {JSONObject(Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8))} catch(_:Exception) {throw ApiFailure(null,"空调状态解码失败")}
                body=body.optJSONObject("statusNow") ?: body
            }
            fun number(key: String)=body.opt(key)?.takeIf {it !is Boolean}?.toString()?.toIntOrNull()
            val state=when(number("status")) {1->"运行（status=1，待实车核对）";2->"关闭（status=2，待实车核对）";else->"未知／未提供"}
            val switch=number("acSwitch")?.let {"\n空调开关原始值：$it（含义待验证）"} ?: ""
            val time=VehicleLockReport(body).reportedAt
            val timeText=time?.let {DateFormat.getDateTimeInstance().format(Date(it))} ?: "未提供，不能判断数据新鲜度"
            return "【熄火参考：空调】\n最近返回：$state$switch\n空调上报时间：$timeText\n当前熄火命令使用 CLOSEAIR；空调关闭不能证明发动机或整车已下电。"
        }
    }
}
