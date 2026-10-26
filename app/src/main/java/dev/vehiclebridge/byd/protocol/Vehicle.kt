package dev.vehiclebridge.byd.protocol

import org.json.JSONObject

enum class DoorAction(val command: String) { LOCK("LOCKDOOR"), UNLOCK("OPENDOOR"), SHUTDOWN("CLOSEAIR"), OPEN_TRUNK("OPENTRUNK"), CLOSE_TRUNK("CLOSETRUNK"), CLIMATE_ON("OPENAIR"), CLIMATE_OFF("CLOSEAIR"), CLIMATE_TEMPERATURE("OPENAIR") }

class Vehicle(val vin: String, val name: String, val model: String, val plate: String, val shared: Boolean, val imageUrl: String = "", val summary: VehicleTelemetry = VehicleTelemetry(emptyList())) {
    val maskedVin get() = if(vin.length > 8) vin.take(3) + "…" + vin.takeLast(4) else "已隐藏"
    val displayName get() = name.ifBlank { model }.ifBlank { "比亚迪车辆" } + " · " + maskedVin
    override fun toString() = "Vehicle([redacted])"
    companion object {
        fun fromJson(value: JSONObject): Vehicle? {
            fun text(key: String) = (value.opt(key) as? String)?.takeIf { it != "null" }?.trim()?.take(100) ?: ""
            val vin=text("vin")
            if(vin.isBlank()) return null
            val empower=value.opt("empowerId")
            val shared=value.optInt("empowerType",0)<0 || (empower!=null && empower!=JSONObject.NULL && empower.toString().isNotBlank())
            val image=(value.opt("diFansVehicleImg") as? String)?.trim()?.takeIf {it.length<=2048 && it!="null"} ?: ""
            return Vehicle(vin,text("autoAlias"),text("modelName"),text("autoPlate"),shared,image,VehicleTelemetry.fromJson(value))
        }
    }
}
