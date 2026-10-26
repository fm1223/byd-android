package dev.vehiclebridge.byd.protocol

/** A gesture commits only its final value for the same vehicle, never on cancel or a busy transition. */
class TemperatureAdjustment {
    private var vehicle: String?=null
    private var initial: Int?=null
    val active get()=vehicle!=null
    fun begin(vin: String?,celsius: Int) {vehicle=vin;initial=celsius}
    fun cancel() {vehicle=null;initial=null}
    fun finish(vin: String?,celsius: Int,busy: Boolean): Int? {
        val accepted=!busy && vehicle!=null && vehicle==vin && initial!=celsius && celsius in ClimateTemperature.MIN..ClimateTemperature.MAX
        cancel();return celsius.takeIf {accepted}
    }
}
