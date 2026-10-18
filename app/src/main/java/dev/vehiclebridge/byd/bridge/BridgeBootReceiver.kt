package dev.vehiclebridge.byd.bridge

import android.content.*
import dev.vehiclebridge.byd.data.AndroidSessionStore
import java.util.concurrent.Executors

class BridgeBootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending=goAsync()
        val executor=Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val saved=AndroidSessionStore.get(context).read()
                if(saved?.binding?.enabled==true && saved.binding.activeDevices.any {saved.pins.containsKey(it.vin)}) BemfaService.start(context)
            } catch(_:Exception) {} finally {pending.finish();executor.shutdown()}
        }
    }
}
