# 远程执行结果与间距调整（0.4.9-dev）

已接入中国区 /control/rc/remoteControlResult，沿用中国区源码的查询参数：公共设备字段、vin、requestSerial、source=app、tboxVersion=3。本版按实车查询返回 242 的反馈，在查询中增加原操作的 commandType：锁车 LOCKDOOR、解锁 OPENDOOR、熄火 CLOSEAIR。该字段进入现有中国区加密和签名流程；请求编号仍沿用原操作编号。查询不传 PIN，不重新发送控制命令。增加 commandType 是否解决 242 仍需服务器验证，不能据查询失败断定车辆操作失败。

优先用控制响应返回的 requestSerial；响应为空时尝试使用发送时保存的原始 requestSerial。后者是否被当前中国区服务器沿用仍待实车验证。
最多查询 5 次，每次间隔 1.5 秒。网络请求仍有超时上限；查询拒绝、网络/解析错误或编号不匹配时立即结束并显示结果未知。不会重发操作、自动登录或改用备用控制命令。查询次数限制不是全部网络请求的硬性总时长。

识别 controlState：0 执行中、1 成功、2 失败；res：1 执行中、2 成功、大于 2 失败。空响应、仅外层 code=0/200、文本 SUCCESS、只有 result 字段、未知状态值都不认作执行成功。

车辆页“最近一次操作”显示查询进度和文字结果；服务器报告成功用绿色、失败用红色、结果未知用橙色。按钮仍只表示操作入口。结果按车辆保留在本次进程内，切换页面不会丢失或误套用到另一辆车；巴法云结果也会更新绑定车辆的最近记录，联动页与后台通知沿用最终结果文本。

锁车并熄火显示两行结果。锁车被明确报告执行失败时不继续发送熄火；锁车已被接受但结果查询未知时仍按用户已开启的组合配置发送熄火，分别报告结果。控制请求被拒绝或发送结果未知的异常仍中止组合操作，无自动重试。

UI 间距：显式给所有页面的输入框使用 Field 样式，卡片内部输入框/按钮间距 12dp，说明文字间距 8dp，卡片间距 16dp；底部导航间距与高度保留原样。

中国区源码参考：
https://github.com/shiqixixixi/byd_china/blob/main/custom_components/byd_china/pybyd_china/client.py

验证：使用合成账号和模拟响应测试查询封装、授权身份、PIN 不进入查询、响应返回编号/原始编号、执行中到成功、失败、空结果、未知状态、查询拒绝与编号错配、最多 5 次查询、只发送一次控制、组合步骤、按车辆保留结果。未发送实车命令。手机未连接，服务器结果与手机实际布局待用户验证。

修改文件：ControlReceipt.kt、CnProtocol.kt、BydApi.kt、SessionRepository.kt、AuthController.kt、MainActivity.kt、activity_main.xml、activity_bemfa.xml、styles.xml、strings.xml、values/colors.xml、values-night/colors.xml、app/build.gradle.kts、RemoteResultTest.kt、ShutdownControlTest.kt、docs/REMOTE_RESULT_TEST.md。

本地验证结果：69 项 JVM 测试全部通过；Debug/Release 构建通过；lintDebug 无错误。
