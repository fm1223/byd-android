package dev.vehiclebridge.byd

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import dev.vehiclebridge.byd.bridge.*
import dev.vehiclebridge.byd.protocol.Vehicle
import dev.vehiclebridge.byd.databinding.ActivityBemfaBinding

internal class BemfaPanel(private val activity: Activity,private val binding: ActivityBemfaBinding,savedInstanceState: Bundle?) {
    private val controller=AuthController.get(activity)
    private var resumed=false
    private var editing=savedInstanceState?.getBoolean("bridgeEditing") ?: false
    private var editingId: String?=savedInstanceState?.getString("bridgeDeviceId")
    private var formLoaded=false
    private var savingDevice=false
    private var vehicleChoices: List<Vehicle> = emptyList()
    private val authObserver: (AuthState)->Unit={render(it)}
    private val statusObserver: (String)->Unit={binding.connectionStatus.text=it}
    init {
        binding.editBindingButton.text="添加设备"
        binding.editBindingButton.setOnClickListener {openEditor(null)}
        binding.cancelDeviceEdit.setOnClickListener {editing=false;editingId=null;formLoaded=false;binding.bemfaKey.text.clear();render(controller.state)}
        binding.deviceFunction.adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,BemfaFunction.entries.map {it.label})
        binding.deviceFunction.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) {binding.functionHint.text=BemfaFunction.entries[position].hint}
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.saveBindingButton.setOnClickListener {
            val info=controller.state.info ?: return@setOnClickListener
            val vehicle=vehicleChoices.getOrNull(binding.boundVehicle.selectedItemPosition-1)
            if(vehicle==null || vehicle.vin !in info.pinVins) {binding.operationStatus.text="请先选择已验证并保存 PIN 的车辆";return@setOnClickListener}
            val function=BemfaFunction.entries[binding.deviceFunction.selectedItemPosition]
            val old=info.binding?.devices?.find {it.id==editingId}
            val key=binding.bemfaKey.text.toString().trim().ifBlank {info.binding?.key ?: ""}
            if(!Regex("[A-Fa-f0-9]{32}").matches(key)) {binding.operationStatus.text="请填写 32 位巴法云用户私钥";return@setOnClickListener}
            try {
                val device=BemfaDevice(binding.bemfaTopic.text.toString().trim(),vehicle.vin,function,
                    binding.deviceName.text.toString().trim().ifBlank {function.label},old?.enabled ?: true)
                binding.bemfaKey.text.clear();savingDevice=true;controller.saveDevice(key,device,editingId)
            } catch(_:IllegalArgumentException) {binding.operationStatus.text="请检查名称与 topic：topic 仅使用字母、数字、下划线或短横线"}
        }
        binding.bridgeEnabled.setOnCheckedChangeListener {_,checked -> if(checked) controller.enableBinding() else BemfaService.stop(activity)}
        BridgeNotifications.ensureChannel(activity)
        binding.showBridgeNotification.setOnClickListener {syncNotificationSwitch();openNotificationSettings()}
        binding.resultNotificationSettings.setOnClickListener {openNotificationSettings(BridgeNotifications.RESULT_CHANNEL)}
        binding.batterySettingsButton.setOnClickListener {
            try {activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${activity.packageName}")))}
            catch(_:Exception) {binding.operationStatus.text="请在系统设置中打开车辆助手的应用信息"}
        }
        controller.observe(authObserver);BridgeStatus.observe(statusObserver);controller.initialize()
    }
    private fun openEditor(device: BemfaDevice?) {
        editing=true;editingId=device?.id;formLoaded=false;render(controller.state)
        binding.bindingEditor.post {binding.bridgeScroll.smoothScrollTo(0,binding.bindingEditor.top)}
    }
    private fun loadForm(device: BemfaDevice?) {
        val info=controller.state.info ?: return
        updateVehicleChoices(device?.vin ?: info.selectedVin ?: info.vehicles.singleOrNull()?.vin)
        binding.deviceFunction.setSelection(device?.function?.ordinal ?: 0)
        binding.deviceName.setText(device?.name ?: "")
        binding.bemfaTopic.setText(device?.topic ?: "")
        binding.bemfaKey.text.clear()
        formLoaded=true
    }
    private fun updateVehicleChoices(preferredVin: String?) {
        val info=controller.state.info ?: return
        vehicleChoices=info.vehicles.filter {it.vin in info.pinVins}
        binding.boundVehicle.adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,listOf("请选择已保存 PIN 的车辆")+vehicleChoices.map {it.displayName})
        binding.boundVehicle.setSelection(vehicleChoices.indexOfFirst {it.vin==preferredVin}+1)
    }
    private fun render(state: AuthState) {
        val info=state.info
        if(info==null) {editing=false;editingId=null;formLoaded=false;binding.bemfaKey.text.clear();binding.deviceName.text.clear();binding.bemfaTopic.text.clear()}
        if(!state.busy && savingDevice) {
            savingDevice=false
            if(state.message=="设备已保存") {editing=false;editingId=null;formLoaded=false}
        }
        val devices=info?.binding?.devices ?: emptyList()
        val showEditor=info!=null && (editing || devices.isEmpty())
        binding.bindingEditor.visibility=if(showEditor) View.VISIBLE else View.GONE
        if(showEditor && !formLoaded) loadForm(devices.find {it.id==editingId})
        if(showEditor && vehicleChoices.map {it.vin}!=info?.vehicles?.filter {it.vin in info.pinVins}?.map {it.vin}) {
            val preferred=vehicleChoices.getOrNull(binding.boundVehicle.selectedItemPosition-1)?.vin ?: devices.find {it.id==editingId}?.vin ?: info?.selectedVin
            updateVehicleChoices(preferred)
        }
        binding.bemfaKey.hint=if(info?.binding!=null) "账号私钥已保存，留空沿用" else "巴法云用户私钥（32 位）"
        binding.editBindingButton.isEnabled=!state.busy && info!=null && devices.size<20
        binding.cancelDeviceEdit.visibility=if(devices.isEmpty()) View.GONE else View.VISIBLE
        binding.saveBindingButton.text=if(editingId==null) "添加并保存设备" else "保存设备修改"
        binding.operationStatus.text=if(showEditor && vehicleChoices.isEmpty()) "请先在设置页为车辆验证并保存 PIN" else state.message
        binding.bridgeProgress.visibility=if(state.busy) View.VISIBLE else View.GONE
        binding.savedBindingSummary.text=if(devices.isEmpty()) "尚未添加设备" else "巴法云账号：私钥已加密保存\n共 ${devices.size} 个设备，${devices.count {it.enabled}} 个启用"
        renderDevices(devices,state)
        listOf(binding.bemfaKey,binding.deviceName,binding.deviceFunction,binding.bemfaTopic,binding.boundVehicle,binding.saveBindingButton,binding.cancelDeviceEdit).forEach {it.isEnabled=!state.busy && info!=null}
        binding.bridgeEnabled.setOnCheckedChangeListener(null)
        binding.bridgeEnabled.isChecked=info?.binding?.enabled==true
        binding.bridgeEnabled.isEnabled=!state.busy && info!=null && devices.any {it.enabled && it.vin in info.pinVins}
        binding.bridgeEnabled.setOnCheckedChangeListener {_,checked -> if(checked) controller.enableBinding() else BemfaService.stop(activity)}
        if(resumed && !state.busy && info?.binding?.enabled==true) BemfaService.start(activity)
    }
    private fun renderDevices(devices: List<BemfaDevice>,state: AuthState) {
        binding.deviceList.removeAllViews()
        val gap=(12*activity.resources.displayMetrics.density).toInt()
        for(device in devices) {
            val card=LinearLayout(activity).apply {
                orientation=LinearLayout.VERTICAL;setPadding(gap,gap,gap,gap)
                setBackgroundResource(R.drawable.button_soft)
                layoutParams=LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=gap}
            }
            val toggle=Switch(activity).apply {
                text=if(device.name==device.function.label) device.name else device.name+" · "+device.function.label
                textSize=15f;setTextColor(activity.getColor(R.color.ink))
                minHeight=(48*activity.resources.displayMetrics.density).toInt();isChecked=device.enabled
                isEnabled=!state.busy
                setOnCheckedChangeListener {_,checked -> controller.setDeviceEnabled(device.id,checked)}
            }
            card.addView(toggle)
            card.addView(TextView(activity).apply {
                text=(state.info?.vehicles?.find {it.vin==device.vin}?.displayName ?: "车辆不可用，请编辑或删除")+"\ntopic："+device.topic+"\n"+device.function.hint
                textSize=13f;setTextColor(activity.getColor(R.color.muted));setPadding(0,gap/2,0,gap/2)
            })
            val actions=LinearLayout(activity)
            actions.addView(Button(activity,null,0,R.style.ActionButton).apply {text="编辑";isEnabled=!state.busy;setOnClickListener {openEditor(device)}},LinearLayout.LayoutParams(0,(48*activity.resources.displayMetrics.density).toInt(),1f))
            actions.addView(Button(activity,null,0,R.style.ActionButton).apply {
                text="删除";isEnabled=!state.busy
                setOnClickListener {AlertDialog.Builder(activity).setTitle("删除设备绑定？").setMessage("将停止此设备的联动，不会删除巴法云或米家中的设备。")
                    .setNegativeButton("取消",null).setPositiveButton("删除") {_,_ -> if(editingId==device.id) {editing=false;editingId=null;formLoaded=false};controller.removeDevice(device.id)}.show()}
            },LinearLayout.LayoutParams(0,(48*activity.resources.displayMetrics.density).toInt(),1f).apply {marginStart=gap})
            card.addView(actions);binding.deviceList.addView(card)
        }
    }
    private fun syncNotificationSwitch() {binding.showBridgeNotification.isChecked=BridgeNotifications.isVisible(activity)}
    private fun openNotificationSettings(channel: String=BridgeNotifications.CHANNEL) {
        val manager=activity.getSystemService(android.app.NotificationManager::class.java)
        val intent=if(manager.areNotificationsEnabled()) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.packageName).putExtra(Settings.EXTRA_CHANNEL_ID,channel)
        else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.packageName)
        try {activity.startActivity(intent)} catch(_:Exception) {binding.operationStatus.text="请在系统设置中修改车辆助手的通知显示"}
    }
    fun saveState(outState: Bundle) {outState.putBoolean("bridgeEditing",editing);outState.putString("bridgeDeviceId",editingId)}
    fun resume() {resumed=true;syncNotificationSwitch();render(controller.state)}
    fun pause() {resumed=false}
    fun close() {controller.remove(authObserver);BridgeStatus.remove(statusObserver)}
}
