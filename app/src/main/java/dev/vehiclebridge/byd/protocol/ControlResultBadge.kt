package dev.vehiclebridge.byd.protocol

data class ControlResultBadge(val text: String,val success: Boolean?)
/** Summarizes server receipts without turning accepted or unknown outcomes into success. */
fun controlResultBadges(message: String,busy: Boolean,action: DoorAction?=null): List<ControlResultBadge> {
 val names=listOf("调整空调温度","开启空调","关闭空调","打开后备箱","关闭后备箱","解锁","锁车","熄火")
 val name=names.firstOrNull {message.startsWith(it)} ?: action?.let {ControlReceipt(RemoteOutcome.UNKNOWN).describe(it).substringBefore("：")} ?: "操作"
 if(busy) return listOf(ControlResultBadge(name+"执行中",null))
 val combined=message.contains("熄火") && message.contains("锁车")
 fun result(part: String,prefix: String=""): ControlResultBadge = when {
  part.contains("未发送") -> ControlResultBadge(prefix+"未执行",false)
  part.contains("失败") || part.contains("拒绝") -> ControlResultBadge(prefix+"失败",false)
  part.contains("未知") || part.contains("未完成") -> ControlResultBadge(prefix+"待确认",null)
  part.contains("执行成功") -> ControlResultBadge(prefix+"成功",true)
  else -> ControlResultBadge(prefix+"待确认",null)
 }
 if(combined) {
  val lock=message.indexOf("锁车")
  return listOf(result(message.substring(0,lock),"熄火"),result(message.substring(lock),"锁车"))
 }
 if(message=="暂未发送车辆请求" || message.isBlank()) return emptyList()
 return listOf(result(message,name))
}
