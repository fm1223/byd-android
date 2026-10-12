# 中国区熄火命令修正（0.4.7-dev）

用户已确认授权账号在官方 App 可以熄火。此次修正不再将授权账号本身作为失败原因。

发现旧版将海外项目 BydAutoLock 的 TURNOFFENGINE 套用了中国区控制端点。中国区开源项目 shiqixixixi/byd_china 的 button.py 明确将 stop_engine 映射为 CLOSEAIR，其 client.py 使用 /control/rc/remoteControl、controlParamsMap="{}"、autoType="1"、asyncControl="0"、source="app"、tboxVersion 及共享身份 identifierType=1。

修正：Android 熄火动作改为 CLOSEAIR。独立熄火、手动锁车组合、巴法云 off 组合均使用同一动作映射；锁车与解锁命令不变。保留既有 PIN 校验、唤醒、加密签名和身份逻辑，不增加自动重试或备用命令。

错误提示增加阶段：唤醒失败会说明“控制命令未发送”；控制阶段拒绝则说明具体操作（熄火/锁车/解锁）并保留服务器代码。组合操作仍区分“锁车请求已发送；熄火未完成或结果未知”。

源码依据：
- https://github.com/shiqixixixi/byd_china/blob/main/custom_components/byd_china/button.py
- https://github.com/shiqixixixi/byd_china/blob/main/custom_components/byd_china/pybyd_china/client.py
- https://github.com/PoorGrammerA/BydAutoLock/blob/main/app/src/main/java/com/poorgrammera/bydautolock/bydapi/BydConfig.java

验证方式：覆盖安装新版，先单独测试车辆页“熄火”。该映射有中国区源码依据，但没有代用户执行实车操作；实际是否熄火仍须用户验证。242 的含义未有可靠文档，不能认定它等于命令不支持或账号权限不足。如果再次拒绝，新版阶段提示可区分唤醒与控制失败。

修改文件：Vehicle.kt、BydApi.kt、strings.xml、app/build.gradle.kts、DoorTransportTest.kt、ShutdownControlTest.kt、docs/SHUTDOWN_TEST.md。

本地检查：59 项 JVM 测试通过、失败 0；Debug/Release 构建通过，lintDebug 无错误。无已连接手机，尚未验证实车结果。

