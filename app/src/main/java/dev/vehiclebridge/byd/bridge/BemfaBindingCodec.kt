package dev.vehiclebridge.byd.bridge

import org.json.JSONObject

/** Reads the legacy single-device record without changing its routing identity. */
object BemfaBindingCodec {
    fun decode(value: JSONObject): BemfaBinding {
        val devices=value.optJSONArray("devices")?.let {array -> (0 until array.length()).map {i ->
            val row=array.getJSONObject(i)
            BemfaDevice(row.getString("topic"),row.getString("vin"),BemfaFunction.valueOf(row.getString("function")),
                row.getString("name"),row.getBoolean("enabled"),row.getString("id"))
        }}
        return BemfaBinding(value.getString("key"),value.getString("topic"),value.getString("vin"),value.optBoolean("enabled"),value.getString("id"),devices)
    }
    fun encode(value: BemfaBinding): Map<String,Any> = linkedMapOf(
        "key" to value.key,"topic" to value.topic,"vin" to value.vin,"enabled" to value.enabled,"id" to value.id,
        "devices" to value.devices.map {linkedMapOf("topic" to it.topic,"vin" to it.vin,"function" to it.function.name,
            "name" to it.name,"enabled" to it.enabled,"id" to it.id)})
}
