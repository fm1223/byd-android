package dev.vehiclebridge.byd

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Button
import android.view.View
import dev.vehiclebridge.byd.databinding.ActivityMainBinding
import dev.vehiclebridge.byd.bridge.BemfaService
import dev.vehiclebridge.byd.bridge.BridgeStatus
import dev.vehiclebridge.byd.data.AndroidSessionStore

/** Platform-only smoke test. It never creates bindings or sends vehicle commands. */
class BridgeSmokeInstrumentation: Instrumentation() {
    override fun onCreate(arguments: Bundle?) {super.onCreate(arguments);start()}
    override fun onStart() {
        var page: Activity?=null
        try {
            page=startActivitySync(Intent(targetContext,MainActivity::class.java).putExtra("tab",1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            runOnMainSync {
                check(page.findViewById<EditText>(R.id.bemfaKey).text.isEmpty())
                check(page.findViewById<EditText>(R.id.bemfaTopic)!=null)
                val root=page.findViewById<android.widget.LinearLayout>(R.id.rootScroll)
                val bound=ActivityMainBinding.bind(root)
                // Binding must resolve the visible shared navigation, never hidden panel buttons.
                check(bound.bottomNav.parent===root)
                listOf(bound.navVehicle,bound.navBridge,bound.navSettings).forEach {
                    check(it.parent===bound.bottomNav)
                    check(it.hasOnClickListeners())
                }
                if(bound.bottomNav.visibility==View.VISIBLE) {
                    bound.navSettings.performClick();check(bound.settingsPage.visibility==View.VISIBLE)
                    bound.navBridge.performClick();check(bound.bridgePanel.root.visibility==View.VISIBLE)
                    bound.navVehicle.performClick();check(bound.vehiclePage.visibility==View.VISIBLE)
                    check(bound.bridgePanel.root.visibility==View.GONE)
                }
            }
            val emptyBinding=AndroidSessionStore.get(targetContext).read()?.binding==null
            if(emptyBinding) {
                // With no binding, the service exercises foreground creation then stops.
                BridgeStatus.update("正在验证后台服务")
                runOnMainSync {targetContext.startForegroundService(Intent(targetContext,BemfaService::class.java))}
                Thread.sleep(1500)
                check(!BemfaService.running)
                check(BridgeStatus.text=="后台联动已停止")
            }
            finish(Activity.RESULT_OK,Bundle().apply {putString("stream","\nBridge UI passed; empty-binding foreground lifecycle checked=$emptyBinding. No vehicle command sent.\n")})
        } catch(_:Throwable) {
            finish(Activity.RESULT_CANCELED,Bundle().apply {putString("stream","\nBridge UI/foreground smoke test failed; no private exception printed.\n")})
        } finally {page?.let {runOnMainSync {it.finish()}}}
    }
}
