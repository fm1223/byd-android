package dev.vehiclebridge.byd.protocol

/** CN temperature encoding, anchored by the user's measured 9 = 25 C. */
object ClimateTemperature {
    const val MIN=17
    const val MAX=31
    const val DEFAULT=25
    fun encode(celsius: Int): Int {
        require(celsius in MIN..MAX) {"温度应为17～31℃"}
        return celsius-16
    }
}
