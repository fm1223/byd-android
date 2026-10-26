package dev.vehiclebridge.byd.protocol

/** Only a usable powered-on report permits an independent HVAC read. */
fun VehicleStatus?.canReadClimate(): Boolean = this?.canShowReportedState==true && report?.power==3

/** A usable powered-off report takes precedence over any cached HVAC response. */
fun VehicleStatus?.effectiveClimate(cached: ClimateReport?): ClimateReport = when {
    this?.canShowReportedState!=true -> ClimateReport()
    report?.power==1 -> ClimateReport(running=false)
    report?.power==3 -> cached ?: ClimateReport()
    else -> ClimateReport()
}
