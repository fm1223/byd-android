package dev.vehiclebridge.byd.data
import dev.vehiclebridge.byd.protocol.*
import org.junit.Assert.*
import org.junit.Test
class ControlResultBadgeTest {
 @Test fun combinedCommandsKeepSeparateOutcomes() {
  val badges=controlResultBadges("熄火：服务器报告执行成功\n锁车：服务器报告执行失败",false)
  assertEquals(listOf(ControlResultBadge("熄火成功",true),ControlResultBadge("锁车失败",false)),badges)
 }
 @Test fun acceptedAndUnknownNeverBecomeSuccess() {
  assertNull(controlResultBadges("开启空调：请求已发送",false).single().success)
  assertNull(controlResultBadges("结果未知",false).single().success)
  assertEquals("锁车成功",controlResultBadges("锁车：服务器报告执行成功",false).single().text)
  assertEquals("操作失败",controlResultBadges("服务器拒绝操作",false).single().text)
  assertTrue(controlResultBadges("暂未发送车辆请求",false).isEmpty())
 }
 @Test fun standaloneFailureKeepsActionName() {
  assertEquals("解锁失败",controlResultBadges("服务器拒绝操作",false,DoorAction.UNLOCK).single().text)
  assertEquals("开启空调成功",controlResultBadges("开启空调：服务器报告执行成功",false).single().text)
 }
}
