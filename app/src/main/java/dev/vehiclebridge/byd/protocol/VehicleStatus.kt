package dev.vehiclebridge.byd.protocol

import java.text.DateFormat
import java.util.Date

/** Last cloud report. State is never inferred from a control acknowledgement. */
class VehicleStatus(val report: VehicleStateReport?=null, val vehicleError: String?=null, val hvac: String?=null, val notBefore: Long?=null) {
    val awaitingReport get()=notBefore?.let {minimum -> (report?.locks?.reportedAt ?: 0L)<minimum}==true
    val explicitlyOffline get()=report?.onlineState==2
    val canShowReportedState get()=report!=null && !explicitlyOffline
    fun reportedDoorAction(): DoorAction? {
        if(explicitlyOffline) return null
        val locks=report?.locks?.locks ?: return null
        return when {locks.all {it==2}->DoorAction.UNLOCK;locks.all {it==1}->DoorAction.LOCK;else->null}
    }
    fun reportedTrunkAction(): DoorAction? = if(explicitlyOffline) null else when(report?.trunk) {0->DoorAction.OPEN_TRUNK;1->DoorAction.CLOSE_TRUNK;else->null}
    fun doorAction(now: Long=System.currentTimeMillis()): DoorAction? {
        val locks=report?.locks ?: return null
        if(awaitingReport || !locks.completeAndRecent(now)) return null
        return when {locks.locks.all {it==2}->DoorAction.UNLOCK;locks.locks.all {it==1}->DoorAction.LOCK;else->null}
    }
    fun trunkAction(now: Long=System.currentTimeMillis()): DoorAction? {
        val value=report ?: return null
        if(awaitingReport || !value.locks.recent(now)) return null
        return when(value.trunk) {0->DoorAction.OPEN_TRUNK;1->DoorAction.CLOSE_TRUNK;else->null}
    }
    fun summary(now: Long=System.currentTimeMillis()): String {
        val value=report ?: return "状态读取失败："+(vehicleError ?: "请刷新后重试")
        val door=when {value.locks.locks.all {it==2}->"四门锁定";value.locks.locks.all {it==1}->"四门未锁";value.locks.locks.any {it==1}->"部分车门未锁";else->"未知／数据不完整"}
        val trunk=when(value.trunk) {0->"关闭";1->"打开";else->"未知"}
        val power=when(value.power) {1->"下电";3->"上电";else->"未知"}
        val time=value.locks.reportedAt?.let {DateFormat.getDateTimeInstance().format(Date(it))} ?: "未提供"
        val age=if(awaitingReport) "等待操作后的车辆上报；请稍后刷新" else if(value.locks.recent(now)) "最近上报，可能存在延迟" else "数据过期或时间未知，请刷新并现场确认"
        return "车门：$door · 后备箱：$trunk\n电源：$power（熄火参考）\n车辆上报：$time\n$age\n空调与电源不能直接证明发动机已熄火。"
    }
    fun describe()=(report?.describe() ?: "【车锁／后备箱／车辆电源】\n读取失败："+vehicleError)+"\n\n"+(hvac ?: "空调状态未知")
    override fun toString()="VehicleStatus([redacted])"
}
