package dev.vehiclebridge.byd.protocol

import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Diagnostic cloud report, with missing and stale data kept explicit. */
class VehicleLockReport(data: JSONObject) {
    private val body=data.optJSONObject("vehicleInfo") ?: data
    private val fields=listOf("leftFrontDoorLock","rightFrontDoorLock","leftRearDoorLock","rightRearDoorLock")
    val locks=fields.map {key -> body.opt(key)?.takeIf {it !is Boolean}?.toString()?.toIntOrNull()?.takeIf {it==1 || it==2}}
    val reportedAt=(body.opt("time") ?: body.opt("timestamp"))?.takeIf {it !is Boolean}?.toString()?.toLongOrNull()?.takeIf {it>0 && it<10_000_000_000_000L}?.let {if(it<10_000_000_000L) it*1000 else it}
    fun recent(now: Long)=reportedAt?.let {it in (now-120_000)..(now+30_000)}==true
    fun completeAndRecent(now: Long)=locks.all {it!=null} && recent(now)
    fun describe(now: Long=System.currentTimeMillis()): String {
        val status=when {locks.all {it==2}->"全部锁定";locks.any {it==1}->"检测到未锁车门";else->"未知"}
        val age=when {reportedAt==null->"上报时间缺失，不能确认当前状态";!recent(now)->"上报时间过旧或异常，不能确认当前状态";locks.any {it==null}->"门锁数据不完整";else->"近两分钟内上报，仍可能存在延迟"}
        return "最近上报：$status\n$age\n"+listOf("左前","右前","左后","右后").mapIndexed {i,name -> name+"："+when(locks[i]) {1->"未锁（1）";2->"锁定（2）";else->"未知／无效"}}.joinToString(" · ")+"\n车辆上报时间："+(reportedAt?.let {DateFormat.getDateTimeInstance().format(Date(it))} ?: "未提供")
    }
    override fun toString()="VehicleLockReport([redacted])"
}
