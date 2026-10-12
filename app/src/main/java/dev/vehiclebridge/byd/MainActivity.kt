package dev.vehiclebridge.byd

import android.app.Activity
import android.content.Intent
import dev.vehiclebridge.byd.bridge.BemfaService
import dev.vehiclebridge.byd.bridge.BridgeStatus
import dev.vehiclebridge.byd.bridge.showBridgeStatus
import android.app.AlertDialog
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.text.InputType
import android.text.InputFilter
import android.os.Bundle
import android.os.Build
import android.view.View
import android.view.WindowInsets
import dev.vehiclebridge.byd.databinding.ActivityMainBinding
import dev.vehiclebridge.byd.protocol.*
import java.text.DateFormat
import java.util.Date

class MainActivity: Activity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: AuthController
    private var resumed=false
    private var selectedTab=0
    private val settingsPage get() = selectedTab==2
    private lateinit var bridgePanel: BemfaPanel
    private val scrollPositions=IntArray(3)
    private lateinit var imageLoader: VehicleImageLoader
    private var lastControlMessage="暂未发送车辆请求"
    private var manualRequest=false
    private var renderedVehicleVin: String?=null
    private var pinDialog: AlertDialog? = null
    private val bridgeObserver: (String)->Unit={binding.bridgeStatusBadge.showBridgeStatus(controller.state.info?.binding?.enabled==true)}
    private val observer: (AuthState)->Unit = {render(it)}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding=ActivityMainBinding.inflate(layoutInflater);setContentView(binding.root)
        binding.root.setOnApplyWindowInsetsListener { view,insets ->
            if(Build.VERSION.SDK_INT>=30) {
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            }
            insets
        }
        controller=AuthController.get(this)
        imageLoader=VehicleImageLoader(binding.vehicleImage,binding.vehicleImageStatus)
        selectedTab=(savedInstanceState?.getInt("tab") ?: intent.getIntExtra("tab",if(intent.getBooleanExtra("settings",false)) 2 else 0)).coerceIn(0,2)
        bridgePanel=BemfaPanel(this,binding.bridgePanel,savedInstanceState)
        binding.navVehicle.setOnClickListener {selectTab(0)}
        binding.navBridge.setOnClickListener {selectTab(1)}
        binding.navSettings.setOnClickListener {selectTab(2)}
        binding.pinPrompt.setOnClickListener {selectTab(2)}
        binding.bridgeStatusBadge.setOnClickListener {selectTab(1)}
        binding.notificationSettings.setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName))
        }
        binding.systemSettings.setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))
        }
        binding.licenseButton.setOnClickListener {
            val notices=listOf("MIT.txt","THIRD_PARTY_NOTICES.txt","Paho-EDL-1.0.txt").joinToString("\n\n") {file -> assets.open("licenses/$file").bufferedReader().use {it.readText()}}
            AlertDialog.Builder(this).setTitle("开源许可").setMessage("车辆助手：MIT\n\nPaho MQTT：EDL 1.0\n"+notices+"\n\nOkHttp / Okio：Apache 2.0；Kotlin：Apache 2.0").setPositiveButton("关闭",null).show()
        }
        binding.aboutVersion.text="版本 "+packageManager.getPackageInfo(packageName,0).versionName+" · MIT 开源"
        binding.loginButton.setOnClickListener {
            try {
                val username=binding.username.text.toString().trim()
                val password=binding.password.text.toString()
                val id=binding.deviceId.text.toString().trim().uppercase()
                val config=if(id.isBlank()) AccountConfig(username,password,(binding.brand.selectedItemPosition+1).toString()) else AccountConfig(username,password,(binding.brand.selectedItemPosition+1).toString(),id)
                binding.password.text.clear()
                controller.login(config)
            } catch(_:IllegalArgumentException) {binding.statusText.setText(R.string.invalid_credentials)}
        }
        binding.advancedToggle.setOnClickListener {
            val visibility=if(binding.deviceId.visibility==View.GONE) View.VISIBLE else View.GONE
            binding.deviceId.visibility=visibility
            binding.deviceIdNote.visibility=visibility
        }
        binding.checkButton.setOnClickListener {controller.check()}
        binding.logoutButton.setOnClickListener {binding.password.text.clear();controller.logout()}
        binding.setPinButton.setOnClickListener {
            val vehicle=controller.state.info?.selected ?: return@setOnClickListener
            pinDialog("验证并保存 PIN · " + vehicle.displayName) {controller.savePin(vehicle.vin,it)}
        }
        binding.removePinButton.setOnClickListener {controller.state.info?.selected?.let {controller.removePin(it.vin)}}
        binding.unlockButton.setOnClickListener {sendDoor(DoorAction.UNLOCK)}
        binding.lockButton.setOnClickListener {sendDoor(DoorAction.LOCK)}
        binding.shutdownButton.setOnClickListener {sendDoor(DoorAction.SHUTDOWN)}
        binding.shutdownOnLock.setOnCheckedChangeListener {_,enabled ->
            controller.state.info?.selected?.let {controller.setShutdownOnLock(it.vin,enabled)}
        }
        binding.bemfaButton.setOnClickListener {selectTab(1)}
        controller.observe(observer);BridgeStatus.observe(bridgeObserver);controller.initialize()
    }
    private fun pinDialog(title: String, submit: (String)->Unit) {
        if(controller.state.busy) return
        val field=EditText(this).apply {
            inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters=arrayOf(InputFilter.LengthFilter(6));isSingleLine=true;isSaveEnabled=false
            importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO;hint="六位操作 PIN"
        }
        val dialog=AlertDialog.Builder(this).setTitle(title).setView(field).setNegativeButton("取消",null)
            .setPositiveButton("确定",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pin=field.text.toString()
            if(!Regex("[0-9]{6}").matches(pin)) {field.error="请输入六位数字";return@setOnClickListener}
            if(controller.state.busy) {dialog.dismiss();return@setOnClickListener}
            field.text.clear();dialog.dismiss();submit(pin)
        }}
        dialog.setOnDismissListener {field.text.clear();pinDialog=null}
        pinDialog=dialog
        dialog.show()
    }
    private fun sendDoor(action: DoorAction) {
        val info=controller.state.info ?: return
        val vehicle=info.selected ?: return
        if(controller.state.busy) return
        if(info.hasPin) {manualRequest=true;controller.sendDoor(vehicle.vin,action)}
        else pinDialog("输入 PIN · " + vehicle.displayName) {manualRequest=true;controller.sendDoor(vehicle.vin,action,it)}
    }
    private fun render(state: AuthState) {
        if(renderedVehicleVin!=state.info?.selectedVin) {
            renderedVehicleVin=state.info?.selectedVin;lastControlMessage="暂未发送车辆请求";manualRequest=false
        }
        val trackingManual=manualRequest
        if(manualRequest) {
            lastControlMessage=state.message
            if(!state.busy) manualRequest=false
        }
        if(!state.busy && !trackingManual) state.info?.selectedControlMessage?.let {lastControlMessage=it}
        binding.controlResult.text=lastControlMessage
        binding.controlProgress.visibility=if(manualRequest && state.busy) View.VISIBLE else View.INVISIBLE
        binding.controlResult.setTextColor(getColor(when {
            lastControlMessage.contains("未知") -> R.color.result_unknown
            lastControlMessage.contains("失败") || lastControlMessage.contains("拒绝") -> R.color.result_failure
            !state.busy && lastControlMessage.contains("执行成功") -> R.color.result_success
            else -> R.color.ink
        }))
        binding.statusText.text=state.message
        binding.statusText.visibility=if(state.info==null || state.busy || settingsPage || (!manualRequest && state.message !in listOf("已复用保存的会话","登录成功，信息已加密保存","会话已通过静默登录恢复","已选择车辆","请求已发送，车辆实际状态请现场确认"))) View.VISIBLE else View.GONE
        binding.vehiclePage.visibility=if(settingsPage) View.GONE else View.VISIBLE
        binding.settingsPage.visibility=if(settingsPage) View.VISIBLE else View.GONE
        binding.pageTitle.text=when(selectedTab) {1 -> "设备联动";2 -> "设置";else -> "我的车辆"}
        binding.pageSubtitle.text=when(selectedTab) {1 -> "让设备指令连接你的车辆";2 -> "账号、安全与后台偏好";else -> "车辆与生活，轻松连接"}
        binding.bottomNav.visibility=if(state.info!=null) View.VISIBLE else View.GONE
        styleNavigation(binding.navVehicle,binding.navBridge,binding.navSettings,selectedTab)
        binding.contentScroll.visibility=if(selectedTab==1 && state.info!=null) View.GONE else View.VISIBLE
        binding.bridgePanel.root.visibility=if(selectedTab==1 && state.info!=null) View.VISIBLE else View.GONE
        binding.progress.visibility=if(state.busy) View.VISIBLE else View.GONE
        listOf(binding.loginButton,binding.checkButton,binding.logoutButton,binding.username,binding.password,binding.brand,binding.deviceId).forEach {it.isEnabled=!state.busy}
        binding.loginForm.visibility=if(state.info==null) View.VISIBLE else View.GONE
        binding.sessionPanel.visibility=if(state.info!=null) View.VISIBLE else View.GONE
        val info=state.info
        binding.bridgeStatusBadge.showBridgeStatus(info?.binding?.enabled==true)
        info?.let { binding.sessionSummary.text=getString(R.string.session_summary,it.vehicleCount,DateFormat.getDateTimeInstance().format(Date(it.createdAt))) }
        val vehicles=info?.vehicles ?: emptyList()
        binding.vehicleChoice.onItemSelectedListener=null
        val choices=if(vehicles.size==1) vehicles.map {it.displayName} else listOf("请选择车辆") + vehicles.map {it.displayName}
        binding.vehicleChoice.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,choices)
        val index=vehicles.indexOfFirst {it.vin==info?.selectedVin}
        binding.vehicleChoice.setSelection(if(vehicles.size==1) 0 else if(index<0) 0 else index+1)
        binding.vehicleChoice.visibility=if(vehicles.size>1) View.VISIBLE else View.GONE
        binding.vehicleChoiceLabel.visibility=if(vehicles.size>1) View.VISIBLE else View.GONE
        binding.vehicleChoice.isEnabled=!state.busy
        binding.vehicleChoice.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) {
                val vehicle=vehicles.getOrNull(if(vehicles.size==1) position else position-1) ?: return
                if(!controller.state.busy && controller.state.info?.selectedVin!=vehicle.vin) controller.select(vehicle.vin)
            }
        }
        val vehicle=info?.selected
        binding.vehicleName.text=vehicle?.name?.ifBlank {vehicle.model}?.ifBlank {"比亚迪车辆"} ?: "选择你的车辆"
        binding.vehicleSubtitle.text=vehicle?.plate?.ifBlank {"你的专属车辆空间"} ?: "请选择要操作的车辆"
        binding.vehicleDetails.text=vehicle?.let {
            listOf("车型     "+it.model.ifBlank {"未提供"},"车牌     "+it.plate.ifBlank {"未提供"},
                "身份     "+if(it.shared) "授权使用者" else "车主").joinToString("\n")
        } ?: if(vehicles.isEmpty()) "账号未返回车辆" else "请选择要操作的车辆"
        binding.vehicleVin.text=vehicle?.let {"VIN  "+it.maskedVin} ?: ""
        binding.pinVehicle.text=vehicle?.let {"当前车辆："+it.displayName} ?: "请先在车辆页选择车辆"
        binding.pinPrompt.text=if(info?.hasPin==true) "PIN 已保存 · 可直接操作" else "未保存 PIN · 点击前往设置"
        binding.bridgeTarget.text=info?.binding?.let {value -> "绑定车辆："+(vehicles.find {it.vin==value.vin}?.displayName ?: "车辆不可用")+"\ntopic："+value.topic} ?: "绑定巴法云设备，使用 on / off 控制车锁"
        imageLoader.load(vehicle?.imageUrl ?: "")
        binding.telemetryCard.visibility=View.GONE
        val report=vehicle?.let {info.telemetry[it.vin] ?: it.summary}
        binding.telemetryValues.text=report?.rows?.takeIf {it.isNotEmpty()}?.joinToString("\n") {it.first+"     "+it.second} ?: "总里程、胎压、能耗：未提供"
        binding.telemetryTimestamp.text=report?.reportedAt?.let {"车辆上报："+DateFormat.getDateTimeInstance().format(Date(it))} ?: "车辆未提供上报时间"
        binding.telemetryMessage.text=if(state.message.contains("车辆数据")) state.message else getString(R.string.telemetry_hint)
        binding.refreshTelemetry.isEnabled=false
        binding.pinStatus.text=if(info?.hasPin==true) "操作 PIN：••••••（已验证并加密保存）" else "操作 PIN：未保存"
        listOf(binding.setPinButton,binding.unlockButton,binding.lockButton,binding.shutdownButton).forEach {it.isEnabled=!state.busy && vehicle!=null}
        binding.shutdownOnLock.setOnCheckedChangeListener(null)
        binding.shutdownOnLock.isChecked=vehicle?.vin in (info?.shutdownOnLock ?: emptySet())
        binding.shutdownOnLock.isEnabled=!state.busy && vehicle!=null
        binding.shutdownOnLock.setOnCheckedChangeListener {_,enabled -> vehicle?.let {controller.setShutdownOnLock(it.vin,enabled)}}
        binding.lockButton.text=if(binding.shutdownOnLock.isChecked) "锁车并熄火" else "锁车"
        binding.shutdownPreferenceVehicle.text=vehicle?.let {"当前车辆："+it.displayName} ?: "请先选择车辆"
        binding.bemfaButton.isEnabled=info!=null
        binding.navBridge.isEnabled=info!=null
        if(resumed && !state.busy && info?.binding?.enabled==true) BemfaService.start(this)
        binding.removePinButton.isEnabled=!state.busy && vehicle!=null && info?.hasPin==true
    }
    private fun selectTab(requested: Int) {
        val tab=requested.coerceIn(0,2)
        if(tab==selectedTab) return
        val previous=selectedTab
        if(previous!=1) scrollPositions[previous]=binding.contentScroll.scrollY
        val content=if(tab==1) binding.bridgePanel.root else binding.contentScroll
        content.animate().cancel();content.alpha=1f;content.translationX=0f
        selectedTab=tab;render(controller.state)
        // Restore before the first visible frame, so a tab never flashes at the wrong scroll offset.
        content.viewTreeObserver.addOnPreDrawListener(object: android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                content.viewTreeObserver.removeOnPreDrawListener(this)
                if(selectedTab!=tab) return true
                if(tab!=1) binding.contentScroll.scrollTo(0,scrollPositions[tab])
                content.translationX=(if(tab>previous) 8 else -8)*resources.displayMetrics.density
                content.alpha=0.92f
                content.animate().translationX(0f).alpha(1f).setDuration(150)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                return true
            }
        })
    }
    override fun onNewIntent(intent: Intent) {super.onNewIntent(intent);setIntent(intent);selectTab(intent.getIntExtra("tab",if(intent.getBooleanExtra("settings",false)) 2 else 0))}
    override fun onSaveInstanceState(outState: Bundle) {outState.putInt("tab",selectedTab);bridgePanel.saveState(outState);super.onSaveInstanceState(outState)}
    override fun onResume() {super.onResume();resumed=true;bridgePanel.resume();render(controller.state)}
    override fun onPause() {resumed=false;bridgePanel.pause();super.onPause()}
    override fun onDestroy() {pinDialog?.dismiss();bridgePanel.close();imageLoader.close();controller.remove(observer);BridgeStatus.remove(bridgeObserver);super.onDestroy()}
}
