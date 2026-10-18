# 多设备绑定说明（0.5.0-dev）

## 使用方式

联动页点击“添加设备”，填写名称、选择车锁或后备箱功能、选择已保存 PIN 的车辆，并填写独立 topic。首次输入巴法云用户私钥，后续留空沿用。全部设备共用一个巴法云账号。

车锁：on 解锁，off 锁车（按车辆设置先熄火再锁车）。后备箱：on 打开，off 关闭。两个功能应使用不同 topic，例如 car001 与 trunk001，在米家中同步成两个独立设备。

每条绑定可编辑、停用或删除。总开关控制整个后台连接，设备开关控制是否订阅该 topic。运行中变更会重新连接，不补执行旧消息。全部停用后总联动停止；重新启用设备后需开启总开关。删除 App 绑定不会删除巴法云或米家中的设备。

支持不同车辆、相同车辆多个功能，最多 20 条；同一账号下 topic 必须唯一。更改私钥会作用于全部设备；删除最后一条绑定也会清除该组巴法云配置。

## 兼容与执行约束

旧 bemfa 单设备记录按原 ID、topic、车辆与总开关读取为车锁设备，无需重新输入 PIN 或私钥。后续写入加密存储时保存 devices 列表。账号与会话格式保持兼容。

一个 MQTT 连接订阅全部启用的 topic，避免以同一私钥重复建立连接。按消息主题查找设备功能与目标车辆，每个设备保留消息过滤和重复抑制，所有设备共享串行车辆执行器；忙碌时丢弃新指令，不排队、不重发。

执行前重新检查配置版本、设备 ID、启用状态、功能是否匹配、车辆及 PIN。编辑生成新设备 ID，配置修改生成新版本 ID，旧连接的延迟回调不能套用到新绑定。移除 PIN 或车辆不可用只停用受影响设备，其余有效设备仍可运行。

## 验证

81 项本地单元测试通过，含旧配置读取、多设备加密记录的序列化往返、按车辆/功能路由、重复 topic 拒绝、旧配置消息拒绝、PIN 移除隔离，以及实际 Paho 客户端对本机模拟 MQTT 服务的一条连接多主题订阅与消息接收。

Debug/Release 构建与 lintDebug 通过。Android 测试包构建通过，但手机禁止安装测试包，未完成仪器自动化测试。主 App 已覆盖安装，在手机确认旧设备迁移、会话复用、联动列表展示及连接恢复。实机多 topic 收发、编辑表单全流程及语音控制仍待用户验证，未发送测试车辆命令。

主要修改：BemfaBinding.kt、BemfaBindingCodec.kt、CommandGate.kt、BemfaMqttClient.kt、BemfaService.kt、BridgeBootReceiver.kt、AndroidSessionStore.kt、SessionRepository.kt、AuthController.kt、BemfaPanel.kt、MainActivity.kt、activity_bemfa.xml、strings.xml、app/build.gradle.kts、README.md 及相关测试。
