package dev.vehiclebridge.byd.protocol

import org.json.JSONObject
import java.math.BigDecimal

/** A cloud report, never a statement of the current door lock state. */
class VehicleTelemetry(val rows: List<Pair<String,String>>,val reportedAt: Long?=null) {
    val hasData get()=rows.isNotEmpty()
    override fun toString()="VehicleTelemetry([redacted])"
    companion object {
        fun fromJson(data: JSONObject): VehicleTelemetry {
            fun number(vararg keys: String): Double?=keys.firstNotNullOfOrNull {key ->
                val value=data.opt(key)
                if(value is Boolean) null else value?.toString()?.toDoubleOrNull()?.takeIf {it.isFinite() && it>=0}
            }
            fun decimal(value: Double)=BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
            fun unit(key: String)=data.optString(key).trim().takeIf {it in setOf("km","mi","kWh/100km","kWh/100 km","L/100km","L/100 km","kWh")}
            fun reading(key: String,unitKey: String?=null): String? {
                val raw=data.opt(key)?.toString()?.trim() ?: return null
                val numeric=raw.toDoubleOrNull()
                if(numeric!=null) return numeric.takeIf {it.isFinite() && it>=0}?.let {decimal(it)+(unitKey?.let {k -> unit(k)}?.let {u -> " $u"} ?: "（单位未提供）")}
                return raw.takeIf {it.length<=60 && Regex("[0-9.]+ ?(?:kWh|L)(?:/100 ?km)?").matches(it)}
            }
            val rows=mutableListOf<Pair<String,String>>()
            val oldMileage=number("totalMileage")
            val newMileage=number("totalMileageV2")
            val mileage=if(newMileage!=null && unit("totalMileageV2Unit")!=null) decimal(newMileage)+" "+unit("totalMileageV2Unit")
                else oldMileage?.let {decimal(it)+" km"} ?: newMileage?.let {decimal(it)+"（单位未提供）"}
            mileage?.let {rows.add("总里程" to it)}
            number("elecPercent","powerBattery")?.takeIf {it<=100}?.let {rows.add("电量" to (decimal(it)+" %"))}
            number("enduranceMileage","evEndurance")?.let {rows.add("纯电续航" to (decimal(it)+" km"))}
            val pressureUnit=when(data.optInt("tirePressUnit",-1)) {1->"bar";2->"psi";3->"kPa";else->null}
            for((key,label) in listOf("leftFront" to "左前胎压","rightFront" to "右前胎压","leftRear" to "左后胎压","rightRear" to "右后胎压")) {
                number(key+"Tirepressure",key+"TirePressure")?.takeIf {it>0}?.let {rows.add(label to (decimal(it)+(pressureUnit?.let {u -> " $u"} ?: "（单位未提供）")))}
            }
            reading("nearestEnergyConsumption","nearestEnergyConsumptionUnit")?.let {rows.add("最近能耗" to it)}
            reading("recent50kmEnergy")?.let {rows.add("近 50 km 能耗" to it)}
            val time=number("time","timestamp")?.takeIf {it>0}?.let {if(it<10_000_000_000) (it*1000).toLong() else it.toLong()}
            return VehicleTelemetry(rows,time)
        }
    }
}
