package dev.vehiclebridge.byd
import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Compatibility entry for existing service notifications. */
class BemfaActivity: Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this,MainActivity::class.java).putExtra("tab",1)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
