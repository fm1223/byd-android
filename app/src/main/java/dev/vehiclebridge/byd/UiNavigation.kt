package dev.vehiclebridge.byd

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.widget.Button

internal fun Activity.styleNavigation(vehicle: Button,bridge: Button,settings: Button,selected: Int) {
    listOf(vehicle,bridge,settings).forEachIndexed {index,button ->
        button.setTextColor(getColor(if(index==selected) R.color.accent else R.color.muted))
        button.compoundDrawables.filterNotNull().forEach {it.mutate().setTint(getColor(if(index==selected) R.color.accent else R.color.muted))}
        button.setTypeface(null,Typeface.NORMAL)
        button.isSelected=index==selected
        button.setBackgroundResource(R.drawable.nav_feedback)
    }
}
internal fun Activity.openMainPage(settings: Boolean) {
    startActivity(Intent(this,MainActivity::class.java).putExtra("settings",settings)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    finish()
}
