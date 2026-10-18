package dev.vehiclebridge.byd.bridge

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.*
import android.os.*
import dev.vehiclebridge.byd.*
import dev.vehiclebridge.byd.data.AndroidSessionStore
import org.eclipse.paho.client.mqttv3.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class BemfaService: Service() {
    companion object {
        private const val CHANNEL=BridgeNotifications.CHANNEL
        private const val NOTIFICATION=20
        private const val STOP="dev.vehiclebridge.byd.STOP_BRIDGE"
        @Volatile var running=false;private set
        fun start(context: Context) {
            if(running) return
            try {context.startForegroundService(Intent(context,BemfaService::class.java))}
            catch(_:Exception) {BridgeStatus.update("系统未允许启动后台服务，请打开应用后重试",BridgeConnection.STOPPED)}
        }
        fun stop(context: Context) {
            AuthController.get(context).stopBridge()
            context.stopService(Intent(context,BemfaService::class.java))
            BridgeStatus.update("后台联动已停止",BridgeConnection.STOPPED)
        }
    }
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadScheduledExecutor()
    private val closed=AtomicBoolean(false)
    @Volatile private var client: BemfaMqttClient?=null
    @Volatile private var binding: BemfaBinding?=null
    @Volatile private var gates: Map<String,CommandGate> = emptyMap()
    private val commandBusy=CommandSlot()
    private var reconnect: ScheduledFuture<*>?=null
    private var retrySeconds=2L
    private lateinit var controller: AuthController
    private lateinit var network: ConnectivityManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private val authObserver: (AuthState)->Unit={syncBinding()}
    @Volatile private var defaultNetwork: Network?=null
    private val networkCallback=object: ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous=defaultNetwork;defaultNetwork=network
            if(previous!=null && previous!=network && !closed.get()) {
                gates.values.forEach {it.ready(false)}
                try {worker.execute {if(!closed.get()) {disconnectClient();status("网络切换，正在重新连接",BridgeConnection.CONNECTING);queueReconnect(0)}}} catch(_:RejectedExecutionException) {}
            } else queueReconnect(0)
        }
        override fun onLost(network: Network) {
            if(defaultNetwork!=network || closed.get()) return
            defaultNetwork=null;gates.values.forEach {it.ready(false)}
            try {worker.execute {if(!closed.get()) {disconnectClient();status("网络断开，等待恢复",BridgeConnection.DISCONNECTED);queueReconnect(retrySeconds)}}} catch(_:RejectedExecutionException) {}
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate();running=true
        BridgeStatus.update("正在启动后台连接",BridgeConnection.CONNECTING)
        BridgeNotifications.ensureChannel(this)
        val note=notification("正在启动后台连接")
        if(Build.VERSION.SDK_INT>=29) startForeground(NOTIFICATION,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION,note)
        wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"VehicleBridge:command")
        wakeLock.setReferenceCounted(false)
        network=getSystemService(ConnectivityManager::class.java)
        try {network.registerDefaultNetworkCallback(networkCallback)} catch(_:Exception) {}
        controller=AuthController.get(this)
        controller.initialize();controller.observe(authObserver)
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action==STOP) {stop(this);return START_NOT_STICKY}
        syncBinding();return START_STICKY
    }
    private fun notification(text: String): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).putExtra("tab",1).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,BemfaService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_app).setContentTitle("车辆助手 · 巴法云联动")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC).addAction(Notification.Action.Builder(null,"停止",stop).build()).build()
    }
    private fun status(text: String, connection: BridgeConnection? = null) {
        if(closed.get()) return
        BridgeStatus.update(text,connection)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(text))
    }
    private fun syncBinding() {
        if(closed.get()) return
        worker.execute {
            if(closed.get()) return@execute
            val saved=try {AndroidSessionStore.get(this).read()} catch(_:Exception) {null}
            val next=saved?.binding?.takeIf {it.enabled && it.activeDevices.isNotEmpty() && it.activeDevices.all {device -> saved.pins.containsKey(device.vin)}}
            if(next==null) {main.post {stopSelf()};return@execute}
            if(binding?.id!=next.id) {
                disconnect();binding=next;gates=next.activeDevices.associate {it.topic to CommandGate(it.topic,function=it.function)};retrySeconds=2
                connect()
            } else if(client?.isConnected!=true && reconnect==null) queueReconnect(0)
        }
    }
    private fun connect() {
        if(closed.get()) return
        val target=binding ?: return
        gates.values.forEach {it.ready(false)}
        disconnectClient()
        status("正在连接巴法云",BridgeConnection.CONNECTING)
        try {
            val current=BemfaMqttClient(target)
            client=current
            current.connect(message=message@{topic,payload,retained,duplicate ->
                if(!closed.get() && client===current) {
                    val device=target.activeDevices.find {it.topic==topic} ?: return@message
                    val activeGate=gates[topic] ?: return@message
                    val delivery=commandBusy.accept(activeGate,topic,payload,retained,duplicate) ?: return@message
                    try {
                        synchronized(wakeLock) {wakeLock.acquire(240_000L)}
                        main.post {
                            if(closed.get() || binding?.id!=target.id) {delivery.finish();releaseWakeLock()}
                            else {
                                try {
                                    status("已收到设备指令，正在执行")
                                    controller.dispatchBridge(device.id,delivery.action,target.id) {result ->
                                        try {status(result)} finally {delivery.finish();releaseWakeLock()}
                                    }
                                } catch(_:Exception) {delivery.finish();releaseWakeLock();status("设备指令未完成，请检查状态；未自动重试")}
                            }
                        }
                    } catch(_:Exception) {delivery.finish();releaseWakeLock();status("设备指令未执行，请重新发送")}

                }
            },lost={
                if(!closed.get() && client===current) {
                    gates.values.forEach {it.ready(false)};status("连接断开，等待自动重连",BridgeConnection.DISCONNECTED);queueReconnect(retrySeconds)
                }
            })
            if(closed.get() || client!==current) return
            retrySeconds=2;gates.values.forEach {it.ready(true)};status("已连接 "+target.activeDevices.size+" 个设备，等待 on／off 指令",BridgeConnection.CONNECTED)
        } catch(e:Exception) {
            gates.values.forEach {it.ready(false)};disconnectClient()
            val code=(e as? MqttException)?.reasonCode
            status("连接／订阅失败"+(code?.let {"（代码 $it）"} ?: "")+"，等待重连",BridgeConnection.DISCONNECTED)
            queueReconnect(retrySeconds)
            retrySeconds=(retrySeconds*2).coerceAtMost(120)
        }
    }
    private fun queueReconnect(seconds: Long) {
        if(closed.get()) return
        try {worker.execute {
            if(closed.get() || client?.isConnected==true) return@execute
            if(reconnect!=null && seconds!=0L) return@execute
            reconnect?.cancel(false)
            reconnect=worker.schedule({reconnect=null;connect()},seconds,TimeUnit.SECONDS)
        }} catch(_:RejectedExecutionException) {}
    }
    private fun disconnectClient() {
        val old=client;client=null
        if(old!=null) {
            old.close()
        }
    }
    private fun disconnect() {gates.values.forEach {it.ready(false)};reconnect?.cancel(false);reconnect=null;disconnectClient()}
    private fun releaseWakeLock() {synchronized(wakeLock) {if(wakeLock.isHeld) wakeLock.release()}}
    override fun onDestroy() {
        closed.set(true);running=false;gates.values.forEach {it.ready(false)}
        controller.remove(authObserver)
        try {network.unregisterNetworkCallback(networkCallback)} catch(_:Exception) {}
        // Wake only while processing a command, never throughout the idle connection.
        releaseWakeLock()
        worker.execute {disconnect()};worker.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        BridgeStatus.update("后台联动已停止",BridgeConnection.STOPPED)
        super.onDestroy()
    }
}
