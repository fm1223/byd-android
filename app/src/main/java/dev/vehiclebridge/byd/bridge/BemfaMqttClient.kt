package dev.vehiclebridge.byd.bridge

import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/** MQTT transport only; it never publishes messages or sends vehicle requests. */
internal class BemfaMqttClient(binding: BemfaBinding, uri: String = BemfaBinding.SERVER_URI) {
    init {java.util.logging.Logger.getLogger("org.eclipse.paho.client.mqttv3").level=java.util.logging.Level.OFF}
    private val topics=binding.activeDevices.map {it.topic}.toTypedArray()
    private val client=MqttAsyncClient(uri,binding.key,MemoryPersistence())
    val isConnected get() = client.isConnected
    fun connect(message: (String,ByteArray,Boolean,Boolean)->Unit,lost: ()->Unit) {
        client.setCallback(object: MqttCallback {
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            override fun connectionLost(cause: Throwable?) {lost()}
            override fun messageArrived(topic: String,value: MqttMessage) {
                message(topic,value.payload,value.isRetained,value.isDuplicate)
            }
        })
        val options=MqttConnectOptions().apply {
            mqttVersion=MqttConnectOptions.MQTT_VERSION_3_1_1;isCleanSession=true
            isAutomaticReconnect=false;connectionTimeout=15;keepAliveInterval=60
        }
        client.connect(options).waitForCompletion(20_000)
        val subscribed=client.subscribe(topics,IntArray(topics.size))
        subscribed.waitForCompletion(15_000)
        if(subscribed.grantedQos.any {it==128}) throw MqttException(128)
    }
    fun close() {
        try {client.disconnectForcibly(0,0,false)} catch(_:Exception) {}
        try {client.close(true)} catch(_:Exception) {}
    }
}
