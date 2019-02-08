package cn.andiedie.tvpause

import android.app.Service.TELEPHONY_SERVICE
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED

class PhoneStateReceiver : BroadcastReceiver() {
    private var lastIdle = true
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        when (intent.action) {
            ACTION_PHONE_STATE_CHANGED -> {
                val tManager = context.getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                if (tManager.callState == TelephonyManager.CALL_STATE_IDLE) {
                    lastIdle = true
                    // resume
                    Intent(context, Service::class.java).also {
                        it.action = Service.ACTION.Resume
                        context.startService(intent)
                    }
                } else if (lastIdle) {
                    lastIdle = false
                    // pause
                    Intent(context, Service::class.java).also {
                        it.action = Service.ACTION.Pause
                        context.startService(intent)
                    }
                }
            }
        }
    }

}