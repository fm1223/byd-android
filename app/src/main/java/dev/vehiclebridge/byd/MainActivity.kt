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
    private var needsStatusRefresh=true
    private val statusHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private val statusTick=object: Runnable {override fun run() {if(resumed) {renderControls(controller.state);statusHandler.postDelayed(this,30_000)}}}
    private var renderedVehicleVin: String?=null
    private var pinDialog: AlertDialog? = null
    private val temperatureAdjustment=TemperatureAdjustment()
    private val temperaturePreferences by lazy {ClimateTemperaturePreferences(this)}
    private val chosenTemperatures=mutableMapOf<String,Int>()
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
        binding.doorButton.setOnClickListener {
            val status=controller.state.info?.vehicleStatus
            chooseAction(status?.reportedDoorAction(),"选择车锁操作",listOf("解锁" to DoorAction.UNLOCK,"锁车" to DoorAction.LOCK))
        }
        binding.trunkButton.setOnClickListener {
            val status=controller.state.info?.vehicleStatus
            chooseAction(status?.reportedTrunkAction(),"选择后备箱操作",listOf("打开后备箱" to DoorAction.OPEN_TRUNK,"关闭后备箱" to DoorAction.CLOSE_TRUNK))
        }
        binding.refreshVehicleStatus.setOnClickListener {controller.state.info?.selected?.let {needsStatusRefresh=false;controller.refreshVehicleStatus(it.vin)}}
        binding.shutdownButton.setOnClickListener {sendDoor(DoorAction.SHUTDOWN)}
        binding.climateButton.setOnClickListener {
            val running=controller.state.info?.vehicleStatus.effectiveClimate(controller.state.climates[controller.state.info?.selectedVin]).running
            chooseAction(when(running) {true->DoorAction.CLIMATE_OFF;false->DoorAction.CLIMATE_ON;null->null},"空调操作",listOf("开启空调" to DoorAction.CLIMATE_ON,"关闭空调" to DoorAction.CLIMATE_OFF))
        }
        binding.climateTemperatureSlider.setOnSeekBarChangeListener(object: android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(slider: android.widget.SeekBar) {
                temperatureAdjustment.begin(controller.state.info?.selectedVin,slider.progress+ClimateTemperature.MIN)
                binding.contentScroll.requestDisallowInterceptTouchEvent(true)
            }
            override fun onProgressChanged(slider: android.widget.SeekBar,progress: Int,fromUser: Boolean) {
                if(fromUser) binding.climateTemperatureValue.text="目标 "+(progress+ClimateTemperature.MIN)+"℃"
            }
            override fun onStopTrackingTouch(slider: android.widget.SeekBar) {
                binding.contentScroll.requestDisallowInterceptTouchEvent(false)
                commitTemperature(slider)
            }
        })
        @Suppress("ClickableViewAccessibility")
        binding.climateTemperatureSlider.setOnTouchListener {_,event ->
            if(event.actionMasked==android.view.MotionEvent.ACTION_CANCEL) temperatureAdjustment.cancel()
            false
        }
        binding.climateTemperatureSlider.accessibilityDelegate=object: View.AccessibilityDelegate() {
            override fun performAccessibilityAction(host: View,action: Int,args: Bundle?): Boolean {
                val setting=action in listOf(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                if(setting) temperatureAdjustment.begin(controller.state.info?.selectedVin,binding.climateTemperatureSlider.progress+ClimateTemperature.MIN)
                val result=super.performAccessibilityAction(host,action,args)
                if(setting) {if(result) commitTemperature(binding.climateTemperatureSlider) else temperatureAdjustment.cancel()}
                return result
            }
        }
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
    private fun chooseAction(action: DoorAction?,title: String,choices: List<Pair<String,DoorAction>>) {
        if(controller.state.busy) return
        if(action!=null) {sendDoor(action);return}
        AlertDialog.Builder(this).setTitle(title).setItems(choices.map {it.first}.toTypedArray()) {_,index ->sendDoor(choices[index].second)}.setNegativeButton("取消",null).show()
    }
    private fun commitTemperature(slider: android.widget.SeekBar) {
        val info=controller.state.info
        val value=temperatureAdjustment.finish(info?.selectedVin,slider.progress+ClimateTemperature.MIN,controller.state.busy || !resumed)
        if(value!=null && info?.selectedVin!=null) {
            chosenTemperatures[info.selectedVin]=value
            temperaturePreferences.set(info.selectedVin,value)
            val running=info.vehicleStatus.effectiveClimate(controller.state.climates[info.selectedVin]).running
            if(running==true) sendDoor(DoorAction.CLIMATE_TEMPERATURE,value) else renderControls(controller.state)
        } else renderControls(controller.state)
    }
    private fun sendDoor(action: DoorAction,temperatureCelsius: Int?=null) {
        val info=controller.state.info ?: return
        val vehicle=info.selected ?: return
        if(controller.state.busy) return
        if(info.hasPin) {manualRequest=true;controller.sendDoor(vehicle.vin,action,temperatureCelsius=temperatureCelsius)}
        else pinDialog("输入 PIN · " + vehicle.displayName) {manualRequest=true;controller.sendDoor(vehicle.vin,action,it,temperatureCelsius)}
    }
    private fun render(state: AuthState) {
        if(renderedVehicleVin!=state.info?.selectedVin) {
            temperatureAdjustment.cancel();needsStatusRefresh=true;renderedVehicleVin=state.info?.selectedVin;lastControlMessage="暂未发送车辆请求";manualRequest=false
        }
        val trackingManual=manualRequest
        if(manualRequest) {
            lastControlMessage=state.message
            if(!state.busy) manualRequest=false
        }
        if(!state.busy && !trackingManual) state.info?.selectedControlMessage?.let {lastControlMessage=it}
        binding.controlResult.text=lastControlMessage
        binding.controlProgress.visibility=if(state.action!=null && state.busy) View.VISIBLE else View.INVISIBLE
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
        binding.bridgeTarget.text=info?.binding?.let {value -> "已配置 "+value.devices.size+" 个设备（启用 "+value.activeDevices.size+" 个）\n"+value.devices.joinToString("、") {it.name+" · "+it.function.label}} ?: "添加巴法云设备，分别控制车锁、后备箱或空调"
        imageLoader.load(vehicle?.imageUrl ?: "")
        renderControls(state)
        binding.pinStatus.text=if(info?.hasPin==true) "操作 PIN：••••••（已验证并加密保存）" else "操作 PIN：未保存"
        listOf(binding.setPinButton,binding.doorButton,binding.shutdownButton,binding.trunkButton).forEach {it.isEnabled=!state.busy && vehicle!=null}
        binding.shutdownOnLock.setOnCheckedChangeListener(null)
        binding.shutdownOnLock.isChecked=vehicle?.vin in (info?.shutdownOnLock ?: emptySet())
        binding.shutdownOnLock.isEnabled=!state.busy && vehicle!=null
        binding.shutdownOnLock.setOnCheckedChangeListener {_,enabled -> vehicle?.let {controller.setShutdownOnLock(it.vin,enabled)}}
        binding.shutdownPreferenceVehicle.text=vehicle?.let {"当前车辆："+it.displayName} ?: "请先选择车辆"
        binding.bemfaButton.isEnabled=info!=null
        binding.navBridge.isEnabled=info!=null
        if(resumed && !state.busy && info?.binding?.enabled==true) BemfaService.start(this)
        binding.removePinButton.isEnabled=!state.busy && vehicle!=null && info?.hasPin==true
        if(resumed && selectedTab==0 && needsStatusRefresh && !state.busy && vehicle!=null) {
            needsStatusRefresh=false
            binding.root.post {if(resumed && selectedTab==0 && !controller.state.busy && controller.state.info?.selectedVin==vehicle.vin) controller.refreshVehicleStatus(vehicle.vin,automatic=true) else needsStatusRefresh=true}
        }
    }
    private fun renderControls(state: AuthState) {
        val info=state.info
        val status=info?.vehicleStatus
        val report=status?.report
        val online=report?.onlineState==1
        val showReported=status?.canShowReportedState==true
        val time=report?.locks?.reportedAt?.let {java.text.SimpleDateFormat("MM-dd HH:mm:ss",java.util.Locale.getDefault()).format(Date(it))}
        binding.reportedTime.text=time?.let {"上报 $it"} ?: ""
        binding.reportedTime.visibility=if(selectedTab==0 && info?.selected!=null && time!=null) View.VISIBLE else View.GONE
        binding.vehicleStatusResult.text=when {
            report==null -> status?.vehicleError?.let {"刷新失败 · $it"} ?: "正在等待车辆状态"
            online -> "车辆在线"
            report.onlineState==2 -> "车辆离线"
            else -> ""
        }
        binding.vehicleStatusResult.visibility=if(binding.vehicleStatusResult.text.isNotEmpty()) View.VISIBLE else View.GONE
        val sameVehicle=state.actionVin==info?.selectedVin
        val doorBusy=state.busy && sameVehicle && state.action in listOf(DoorAction.LOCK,DoorAction.UNLOCK)
        val trunkBusy=state.busy && sameVehicle && state.action in listOf(DoorAction.OPEN_TRUNK,DoorAction.CLOSE_TRUNK)
        val shutdownBusy=state.busy && sameVehicle && (state.action==DoorAction.SHUTDOWN || (state.action==DoorAction.LOCK && info?.selectedVin in (info?.shutdownOnLock ?: emptySet())))
        // The whole tile is a button, but its large label always describes reported state.
        fun tile(view: android.widget.LinearLayout,icon: android.widget.ImageView,title: android.widget.TextView,hint: android.widget.TextView,progress: android.widget.ProgressBar,busy: Boolean,label: String,action: String,image: Int,tone: Int,category: String) {
            val bg=getColor(when(tone) {1->R.color.state_calm_bg;2->R.color.state_active_bg;else->R.color.state_unknown_bg})
            val ink=getColor(when(tone) {1->R.color.state_calm_ink;2->R.color.state_active_ink;else->R.color.state_unknown_ink})
            val shape=android.graphics.drawable.GradientDrawable().apply {setColor(bg);cornerRadius=18*resources.displayMetrics.density}
            view.background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ink and 0x00ffffff or 0x22000000),shape,null)
            view.isEnabled=!state.busy && info?.selected!=null
            view.alpha=if(state.busy && !busy) 0.65f else 1f
            view.contentDescription=category+"，"+(if(busy) "正在执行，请等待" else "$label，$action")
            view.setAccessibilityDelegate(object: View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View,node: android.view.accessibility.AccessibilityNodeInfo) {super.onInitializeAccessibilityNodeInfo(host,node);node.className="android.widget.Button"}
            })
            title.text=if(busy) "执行中" else label;title.setTextColor(ink)
            hint.text=if(busy) "等待反馈" else action;hint.setTextColor(ink)
            icon.setImageResource(image);icon.imageTintList=android.content.res.ColorStateList.valueOf(ink)
            icon.visibility=if(busy) View.INVISIBLE else View.VISIBLE
            progress.indeterminateTintList=android.content.res.ColorStateList.valueOf(ink)
            progress.visibility=if(busy) View.VISIBLE else View.GONE
        }
        val waiting=if(status?.explicitlyOffline==true) "待刷新" else "状态未知"
        val lockLabel=if(info?.selectedVin in (info?.shutdownOnLock ?: emptySet())) "点击熄火并锁车" else "点击锁车"
        val locked=showReported && report?.locks?.locks?.all {it==2}==true
        val unlocked=showReported && report?.locks?.locks?.all {it==1}==true
        val mixed=showReported && !locked && !unlocked && report?.locks?.locks?.any {it==1}==true
        tile(binding.doorButton,binding.doorIcon,binding.doorState,binding.doorHint,binding.doorProgress,doorBusy,
            when {locked->"已锁定";unlocked->"未锁定";mixed->"部分未锁";else->waiting},
            when {locked->"点击解锁";unlocked->lockLabel;else->"点击选择操作"},
            when {locked->R.drawable.ic_state_locked;unlocked || mixed->R.drawable.ic_state_unlocked;else->R.drawable.ic_state_unknown},
            when {locked->1;unlocked || mixed->2;else->0},"车锁")
        val trunkClosed=showReported && report?.trunk==0
        val trunkOpen=showReported && report?.trunk==1
        tile(binding.trunkButton,binding.trunkIcon,binding.trunkState,binding.trunkHint,binding.trunkProgress,trunkBusy,
            when {trunkClosed->"已关闭";trunkOpen->"已打开";else->waiting},
            when {trunkClosed->"点击打开";trunkOpen->"点击关闭";else->"点击选择操作"},
            when {trunkClosed->R.drawable.ic_state_trunk_closed;trunkOpen->R.drawable.ic_state_trunk_open;else->R.drawable.ic_state_unknown},
            when {trunkClosed->1;trunkOpen->2;else->0},"后备箱")
        val powerOff=showReported && report?.power==1
        val powerOn=showReported && report?.power==3
        tile(binding.shutdownButton,binding.shutdownIcon,binding.shutdownState,binding.shutdownHint,binding.shutdownProgress,shutdownBusy,
            when {powerOff->"已下电";powerOn->"已上电";else->waiting},"点击熄火",
            if(powerOff || powerOn) R.drawable.ic_state_power else R.drawable.ic_state_unknown,
            when {powerOff->1;powerOn->2;else->0},"车辆电源，电源状态仅作熄火参考")
        val climate=status.effectiveClimate(state.climates[info?.selectedVin])
        val climateReading=status.canReadClimate() && info?.selectedVin in state.climateBusy
        val climateOperating=state.busy && sameVehicle && state.action in listOf(DoorAction.CLIMATE_ON,DoorAction.CLIMATE_OFF,DoorAction.CLIMATE_TEMPERATURE)
        val climateTarget=climate?.targetCelsius ?: report?.climateTargetCelsius
        val climateLabel=(when(climate?.running) {true->"已开启";false->"已关闭";null->"状态未知"})+(climateTarget?.let {" · $it℃"} ?: " · —℃")
        val climateAction=when {climateReading->"正在读取";climate?.running==true->"点击关闭";climate?.running==false->"点击开启";else->"点击选择操作"}
        tile(binding.climateButton,binding.climateIcon,binding.climateState,binding.climateHint,binding.climateProgress,climateOperating,climateLabel,climateAction,R.drawable.ic_state_climate,when(climate?.running) {true->2;false->1;null->0},"空调目标温度")
        binding.climateButton.isEnabled=!state.busy && !climateReading && info?.selected!=null
        val vin=info?.selectedVin
        if(state.busy || vin!=renderedVehicleVin) temperatureAdjustment.cancel()
        if(!temperatureAdjustment.active) {
            val selected=chosenTemperatures[vin] ?: vin?.let {temperaturePreferences.get(it)} ?: climateTarget ?: ClimateTemperature.DEFAULT
            binding.climateTemperatureSlider.progress=selected-ClimateTemperature.MIN
            binding.climateTemperatureValue.text="目标 $selected℃"
        }
        binding.climateTemperatureSlider.isEnabled=!state.busy && info?.selected!=null
        binding.climateTemperatureNote.text=when {
            climateOperating->"正在执行空调操作"
            climate.running==true->"松手后立即调温，重新请求运行10分钟"
            climate.running==false->"松手后保存，下次开启空调时使用"
            else->"空调状态未知，仅保存下次开启的温度"
        }
        binding.climateTemperatureProgress.visibility=if(climateOperating) View.VISIBLE else View.GONE
        binding.refreshVehicleStatus.text=if(state.busy && state.refreshing) "" else "刷新"
        binding.refreshVehicleStatus.contentDescription=if(state.busy && state.refreshing) "正在刷新车辆状态" else "刷新车辆状态"
        binding.refreshVehicleStatus.isEnabled=!state.busy && info?.selected!=null
        binding.refreshStatusProgress.visibility=if(state.busy && state.refreshing) View.VISIBLE else View.GONE
        if(state.busy && state.action!=null && sameVehicle) binding.controlResult.text=state.message
    }
    private fun selectTab(requested: Int) {
        val tab=requested.coerceIn(0,2)
        if(tab==selectedTab) return
        val previous=selectedTab
        if(previous!=1) scrollPositions[previous]=binding.contentScroll.scrollY
        val content=if(tab==1) binding.bridgePanel.root else binding.contentScroll
        content.animate().cancel();content.alpha=1f;content.translationX=0f
        selectedTab=tab;if(tab==0) needsStatusRefresh=true;render(controller.state)
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
    override fun onResume() {super.onResume();resumed=true;needsStatusRefresh=true;bridgePanel.resume();render(controller.state);statusHandler.removeCallbacks(statusTick);statusHandler.postDelayed(statusTick,30_000)}
    override fun onPause() {temperatureAdjustment.cancel();resumed=false;statusHandler.removeCallbacks(statusTick);bridgePanel.pause();super.onPause()}
    override fun onDestroy() {pinDialog?.dismiss();bridgePanel.close();imageLoader.close();controller.remove(observer);BridgeStatus.remove(bridgeObserver);super.onDestroy()}
}
