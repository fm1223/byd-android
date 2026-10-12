package dev.vehiclebridge.byd

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Isolated public image requests never carry account headers, cookies or PINs. */
internal class VehicleImageLoader(private val image: ImageView, private val caption: TextView) {
    private val main=Handler(Looper.getMainLooper())
    private val client=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS)
        .callTimeout(15,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private var call: Call?=null
    private var current: String?=null
    private var generation=0
    fun load(raw: String) {
        if(current==raw) return
        current=raw;generation++;val requestGeneration=generation
        call?.cancel();image.setImageResource(R.drawable.vehicle_placeholder)
        val parsed=raw.toHttpUrlOrNull()
        if(parsed==null || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            caption.text="暂无车辆图片";return
        }
        // Legacy HTTP image links use the same host over HTTPS, with no cleartext exception.
        val url=parsed.newBuilder().scheme("https").build()
        caption.text="正在加载车辆图片"
        val pending=client.newCall(Request.Builder().url(url).build());call=pending
        pending.enqueue(object: Callback {
            override fun onFailure(call: Call,e: IOException) {showFailure(requestGeneration)}
            override fun onResponse(call: Call,response: Response) {
                val bitmap=try {response.use {
                    val body=it.body ?: throw IOException()
                    if(!it.isSuccessful || body.contentLength()>5*1024*1024) throw IOException()
                    val bytes=body.byteStream().use {stream ->
                        val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while(true) {val read=stream.read(buffer);if(read<0) break
                            if(output.size()+read>5*1024*1024) throw IOException()
                            output.write(buffer,0,read)
                        };output.toByteArray()
                    }
                    val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    if(bounds.outWidth<=0 || bounds.outHeight<=0) throw IOException()
                    var sample=1
                    while(bounds.outWidth/sample>1200 || bounds.outHeight/sample>1200) sample*=2
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample})
                }} catch(_:Exception) {null}
                main.post {if(generation==requestGeneration) {
                    if(bitmap!=null) {image.setImageBitmap(bitmap);caption.text="车辆外观"}
                    else caption.text="图片暂不可用，车辆操作不受影响"
                }}
            }
        })
    }
    private fun showFailure(value: Int) {main.post {if(generation==value) caption.text="图片暂不可用，车辆操作不受影响"}}
    fun close() {generation++;call?.cancel();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
}
