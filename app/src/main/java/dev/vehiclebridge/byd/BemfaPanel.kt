package dev.vehiclebridge.byd

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import dev.vehiclebridge.byd.bridge.*
import dev.vehiclebridge.byd.protocol.Vehicle
import dev.vehiclebridge.byd.databinding.ActivityBemfaBinding

internal class BemfaPanel(private val activity: Activity, private val binding: ActivityBemfaBinding, savedInstanceState: Bundle?) {
    private lateinit var controller: AuthController
    private var resumed=false
    private var loaded=false
    private var editing=false
    private var enabledListener: android.widget.CompoundButton.OnCheckedChangeListener?=null
    private var vehicleChoices: List<Vehicle> = emptyList()
    private val authObserver: (AuthState)->Unit={render(it)}
    private val statusObserver: (String)->Unit={
        binding.connectionStatus.text=it
        updateBridgeButtons(controller.state)
    }
    init {
        editing=savedInstanceState?.getBoolean("bridgeEditing") ?: false
        binding.editBindingButton.setOnClickListener {editing=!editing;render(controller.state)}
        controller=AuthController.get(activity)
        binding.saveBindingButton.setOnClickListener {
            val info=controller.state.info ?: return@setOnClickListener
            val index=binding.boundVehicle.selectedItemPosition-(if(vehicleChoices.size>1) 1 else 0)
            val targetVin=vehicleChoices.getOrNull(index)?.vin
            val vehicle=info.vehicles.find {it.vin==targetVin}
            if(vehicle==null) {binding.operationStatus.text="请选择仍在账号列表中的绑定车辆";return@setOnClickListener}
            val key=binding.bemfaKey.text.toString().trim().ifBlank {info.binding?.key ?: ""}
            try {
                val target=BemfaBinding(key,binding.bemfaTopic.text.toString().trim(),vehicle.vin)
                binding.bemfaKey.text.clear();controller.saveBinding(target)
            } catch(_:IllegalArgumentException) {binding.operationStatus.text="请填写 32 位私钥和 topic；topic 仅使用字母、数字、下划线或短横线"}
        }
        enabledListener=android.widget.CompoundButton.OnCheckedChangeListener {_,checked ->
            if(checked) controller.enableBinding() else BemfaService.stop(activity)
        }
        binding.bridgeEnabled.setOnCheckedChangeListener(enabledListener)
        BridgeNotifications.ensureChannel(activity)
        binding.showBridgeNotification.setOnClickListener {
            syncNotificationSwitch()
            openNotificationSettings()
        }

        binding.resultNotificationSettings.setOnClickListener {openNotificationSettings(BridgeNotifications.RESULT_CHANNEL)}
        binding.batterySettingsButton.setOnClickListener {
            try {activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${activity.packageName}")))}
            catch(_:Exception) {binding.operationStatus.text="请在系统设置中打开车辆助手的应用信息"}
        }

        controller.observe(authObserver);BridgeStatus.observe(statusObserver);controller.initialize()
    }
    private fun render(state: AuthState) {
        val info=state.info
        if(info==null && loaded) {
            loaded=false;editing=false;vehicleChoices=emptyList()
            binding.bemfaKey.text.clear();binding.bemfaTopic.text.clear()
        }
        binding.operationStatus.text=state.message
        if(!state.busy && state.message=="绑定已保存，点击启动后台联动") editing=false
        val showEditor=info?.binding==null || editing
        binding.bindingEditor.visibility=if(showEditor) View.VISIBLE else View.GONE
        binding.editBindingButton.visibility=if(info?.binding!=null) View.VISIBLE else View.GONE
        binding.editBindingButton.text=if(editing) "收起配置" else "修改设备绑定"
        binding.bridgeProgress.visibility=if(state.busy) View.VISIBLE else View.GONE
        if(!loaded && info!=null) {
            vehicleChoices=info.vehicles
            val labels=if(vehicleChoices.size>1) listOf("请选择绑定车辆")+vehicleChoices.map {it.displayName} else vehicleChoices.map {it.displayName}
            binding.boundVehicle.adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,labels)
            val selected=info.binding?.vin ?: info.selectedVin
            val selectedIndex=vehicleChoices.indexOfFirst {it.vin==selected}
            binding.boundVehicle.setSelection(if(vehicleChoices.size>1) selectedIndex+1 else 0)
            binding.bemfaTopic.setText(info.binding?.topic ?: "")
            loaded=true
        }
        binding.bemfaKey.hint=if(info?.binding!=null) "私钥已加密保存，留空沿用" else "巴法云用户私钥（32 位）"
        binding.savedBindingSummary.text=info?.binding?.let {value ->
            "已保存绑定："+(info.vehicles.find {it.vin==value.vin}?.displayName ?: "车辆不可用")+"\ntopic："+value.topic+"\n私钥：••••••\n"+if(value.enabled) "后台联动已启用" else "后台联动已停止"
        } ?: "尚未保存设备绑定"
        listOf(binding.bemfaKey,binding.bemfaTopic,binding.boundVehicle,binding.saveBindingButton).forEach {it.isEnabled=!state.busy && info!=null}
        updateBridgeButtons(state)
        if(resumed && !state.busy && info?.binding?.enabled==true) BemfaService.start(activity)
    }
    private fun updateBridgeButtons(state: AuthState) {
        val value=state.info?.binding
        val active=value?.enabled==true || BemfaService.running
        binding.bridgeEnabled.setOnCheckedChangeListener(null)
        binding.bridgeEnabled.isChecked=active
        binding.bridgeEnabled.isEnabled=if(active) true else !state.busy && value!=null && value.vin in (state.info?.pinVins ?: emptySet())
        binding.bridgeEnabled.setOnCheckedChangeListener(enabledListener)
    }
    private fun syncNotificationSwitch() {
        binding.showBridgeNotification.isChecked=BridgeNotifications.isVisible(activity)
    }
    private fun openNotificationSettings(channel: String = BridgeNotifications.CHANNEL) {
        val manager=activity.getSystemService(android.app.NotificationManager::class.java)
        val intent=if(manager.areNotificationsEnabled()) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE,activity.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID,channel)
        else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.packageName)
        try {activity.startActivity(intent)}
        catch(_:Exception) {
            binding.operationStatus.text="请在系统设置中修改车辆助手的通知显示"
        }
    }
    fun saveState(outState: Bundle) {outState.putBoolean("bridgeEditing",editing)}
    fun resume() {resumed=true;syncNotificationSwitch();render(controller.state)}
    fun pause() {resumed=false}
    fun close() {controller.remove(authObserver);BridgeStatus.remove(statusObserver)}
}
