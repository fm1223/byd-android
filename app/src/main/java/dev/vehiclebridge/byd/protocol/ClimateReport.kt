package dev.vehiclebridge.byd.protocol
import org.json.JSONObject
import java.util.Base64

/** Raw HVAC response; display policy may override it when vehicle power is off. */
class ClimateReport(val running: Boolean?=null,val targetCelsius: Int?=null,val error: String?=null) {
    companion object {
        private fun integer(body: JSONObject,key: String)=body.opt(key)?.takeIf {it !is Boolean}?.toString()?.toDoubleOrNull()?.takeIf {it.isFinite() && it%1==0.0}?.toInt()
        fun targetTemperature(body: JSONObject): Int? {
            val precise=integer(body,"mainSettingTempNew")?.takeIf {it in ClimateTemperature.MIN..ClimateTemperature.MAX}
            val code=integer(body,"mainSettingTemp")?.takeIf {it in 1..15}
            return precise ?: code?.plus(16)
        }
        fun parse(data: JSONObject): ClimateReport {
            var body=data.optJSONObject("statusNow") ?: data.optJSONObject("data") ?: data
            val encoded=body.optString("outputBase64")
            if(encoded.isNotBlank()) {
                if(encoded.length>65536) throw ApiFailure(null,"空调响应过大")
                body=try {JSONObject(Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8))} catch(_:Exception) {throw ApiFailure(null,"空调状态解码失败")}
                body=body.optJSONObject("statusNow") ?: body
            }
            val running=when(integer(body,"status")) {1->true;2->false;else->null}
            return ClimateReport(running,targetTemperature(body))
        }
    }
    override fun toString()="ClimateReport([redacted])"
}
