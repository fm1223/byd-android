package dev.vehiclebridge.byd
import android.content.Context
import dev.vehiclebridge.byd.protocol.*
/** Non-secret desired temperatures; vehicle identifiers are not stored as preference keys. */
class ClimateTemperaturePreferences(context: Context) {
 private val values=context.applicationContext.getSharedPreferences("climate_temperature",Context.MODE_PRIVATE)
 fun get(vin: String): Int?=values.getInt(CnCrypto.md5Upper(vin),0).takeIf {it in ClimateTemperature.MIN..ClimateTemperature.MAX}
 fun set(vin: String,celsius: Int) {ClimateTemperature.encode(celsius);values.edit().putInt(CnCrypto.md5Upper(vin),celsius).apply()}
}
