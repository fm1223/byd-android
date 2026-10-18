package dev.vehiclebridge.byd.bridge

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.ServerSocket
import java.util.concurrent.*

class BemfaMqttClientTest {
    private val binding=BemfaBinding("00112233445566778899aabbccddeeff","car002","L0000000000000001")
    private fun packet(input: InputStream): Pair<Int,ByteArray> {
        val type=input.read();require(type>=0)
        var length=0;var multiplier=1;var count=0
        do {val value=input.read();require(value>=0);length+=(value and 127)*multiplier;multiplier*=128;count++
            require(count<=4);if(value and 128==0) break
        } while(true)
        return type to ByteArray(length).also {DataInputStream(input).readFully(it)}
    }
    @Test fun productionTransportUsesCleanSessionQosZeroAndDeliversRetainFlag() {
        ServerSocket(0).use {server ->
            server.soTimeout=5000
            val executor=Executors.newSingleThreadExecutor()
            val release=CountDownLatch(1)
            val broker=executor.submit {
                server.accept().use {socket ->
                    socket.soTimeout=5000
                    val input=socket.getInputStream();val output=socket.getOutputStream()
                    val connect=packet(input);assertEquals(0x10,connect.first)
                    val data=DataInputStream(ByteArrayInputStream(connect.second))
                    assertEquals("MQTT",data.readUTF());assertEquals(4,data.readUnsignedByte())
                    assertEquals(2,data.readUnsignedByte());assertEquals(60,data.readUnsignedShort())
                    assertEquals(binding.key,data.readUTF());assertEquals(0,data.available())
                    output.write(byteArrayOf(0x20,2,0,0));output.flush()
                    val subscription=packet(input);assertEquals(0x82,subscription.first)
                    val sub=DataInputStream(ByteArrayInputStream(subscription.second));val id=sub.readUnsignedShort()
                    assertEquals(binding.topic,sub.readUTF());assertEquals(0,sub.readUnsignedByte())
                    output.write(byteArrayOf(0x90.toByte(),3,(id shr 8).toByte(),id.toByte(),0));output.flush()
                    val payload=ByteArrayOutputStream().apply {DataOutputStream(this).writeUTF(binding.topic);write("on".toByteArray())}.toByteArray()
                    output.write(byteArrayOf(0x31,payload.size.toByte())+payload);output.flush()
                    assertTrue(release.await(5,TimeUnit.SECONDS))
                }
            }
            val client=BemfaMqttClient(binding,"tcp://127.0.0.1:${server.localPort}")
            val received=LinkedBlockingQueue<Boolean>()
            try {
                client.connect({topic,payload,retained,duplicate ->
                    assertEquals(binding.topic,topic);assertEquals("on",String(payload));assertFalse(duplicate)
                    received.offer(retained)
                },{})
                assertTrue(client.isConnected);assertEquals(true,received.poll(5,TimeUnit.SECONDS))
                release.countDown();broker.get(5,TimeUnit.SECONDS)
            } finally {release.countDown();client.close();executor.shutdownNow()}
        }
    }

    @Test fun oneConnectionSubscribesAllEnabledTopicsAndReceivesEachRoute() {
        val devices=listOf(BemfaDevice("door001",binding.vin),BemfaDevice("trunk001",binding.vin,BemfaFunction.TRUNK),BemfaDevice("paused001",binding.vin,enabled=false))
        val target=BemfaBinding(binding.key,"door001",binding.vin,true,devices=devices)
        ServerSocket(0).use {server ->
            server.soTimeout=5000
            val executor=Executors.newSingleThreadExecutor();val release=CountDownLatch(1)
            val broker=executor.submit {
                server.accept().use {socket ->
                    socket.soTimeout=5000
                    val input=socket.getInputStream();val output=socket.getOutputStream()
                    assertEquals(0x10,packet(input).first)
                    output.write(byteArrayOf(0x20,2,0,0));output.flush()
                    val subscription=packet(input);assertEquals(0x82,subscription.first)
                    val data=DataInputStream(ByteArrayInputStream(subscription.second));val id=data.readUnsignedShort()
                    val topics=mutableListOf<String>()
                    while(data.available()>0) {topics.add(data.readUTF());assertEquals(0,data.readUnsignedByte())}
                    assertEquals(listOf("door001","trunk001"),topics)
                    output.write(byteArrayOf(0x90.toByte(),4,(id shr 8).toByte(),id.toByte(),0,0));output.flush()
                    for(topic in topics) {
                        val payload=ByteArrayOutputStream().apply {DataOutputStream(this).writeUTF(topic);write("off".toByteArray())}.toByteArray()
                        output.write(byteArrayOf(0x30,payload.size.toByte())+payload);output.flush()
                    }
                    assertTrue(release.await(5,TimeUnit.SECONDS))
                }
            }
            val received=LinkedBlockingQueue<String>()
            val client=BemfaMqttClient(target,"tcp://127.0.0.1:"+server.localPort)
            try {
                client.connect({topic,payload,retained,duplicate ->
                    assertEquals("off",String(payload));assertFalse(retained);assertFalse(duplicate);received.offer(topic)
                },{})
                assertEquals(setOf("door001","trunk001"),setOf(received.poll(5,TimeUnit.SECONDS),received.poll(5,TimeUnit.SECONDS)))
                release.countDown();broker.get(5,TimeUnit.SECONDS)
            } finally {release.countDown();client.close();executor.shutdownNow()}
        }
    }
}
