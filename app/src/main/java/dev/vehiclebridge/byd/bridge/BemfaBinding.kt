package dev.vehiclebridge.byd.bridge

import java.util.UUID

/** One device binding; its vehicle is independent of the UI selection. */
class BemfaBinding(val key: String, val topic: String, val vin: String, val enabled: Boolean = false,
    val id: String = UUID.randomUUID().toString()) {
    init {
        require(Regex("[A-Fa-f0-9]{32}").matches(key))
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(topic))
        require(Regex("[A-HJ-NPR-Z0-9]{17}").matches(vin))
        require(id.isNotBlank() && id.length<=64)
    }
    fun enabled(value: Boolean) = BemfaBinding(key,topic,vin,value,id)
    override fun toString() = "BemfaBinding([redacted])"
    companion object { const val SERVER_URI="tcp://mqtt.bemfa.com:9501" }
}
