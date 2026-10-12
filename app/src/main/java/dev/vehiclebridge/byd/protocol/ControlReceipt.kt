package dev.vehiclebridge.byd.protocol

/** A receipt for one command; never interpreted as persistent vehicle state. */
enum class RemoteOutcome { ACCEPTED, PENDING, SUCCESS, FAILURE, UNKNOWN }
data class ControlReceipt(val outcome: RemoteOutcome, val serial: String? = null, val detail: String = "") {
    override fun toString()="ControlReceipt(outcome=$outcome, serial=[redacted])"
    fun describe(action: DoorAction): String {
        val name=when(action) {DoorAction.LOCK -> "锁车";DoorAction.UNLOCK -> "解锁";DoorAction.SHUTDOWN -> "熄火"}
        return name+"："+when(outcome) {
            RemoteOutcome.SUCCESS -> "服务器报告执行成功"
            RemoteOutcome.FAILURE -> "服务器报告执行失败"
            RemoteOutcome.PENDING -> "执行中"
            RemoteOutcome.ACCEPTED -> "请求已发送"
            RemoteOutcome.UNKNOWN -> "请求已发送，结果未知"
        }+(if(detail.isNotBlank()) "（$detail）" else "")
    }
}
