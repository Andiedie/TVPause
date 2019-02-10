package cn.andiedie.tvpause

import android.app.*
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log

private val TAG = "TVPause." + PhoneStateReceiverService::class.java.simpleName

class PhoneStateReceiverService : Service() {
    companion object {
        const val START = "cn.andiedie.TVPause.PhoneStateReceiverService.START"
        const val STOP = "cn.andiedie.TVPause.PhoneStateReceiverService.STOP"
        private const val CHANNEL_ID = "cn.andiedie.TVPause.PhoneStateReceiverService.CHANNEL"
        private const val NOTIFICATION_ID = 19210
    }
    private var registered = false

    private val phoneStateReceiver = object : BroadcastReceiver() {
        private var lastIdle = true
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null || intent == null) return
            when (intent.action) {
                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                    val tManager = context.getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                    if (tManager.callState == TelephonyManager.CALL_STATE_IDLE) {
                        lastIdle = true
                        // resume
                        Intent(context, TVService::class.java).also {
                            it.action = TVService.RESUME
                            startService(it)
                        }
                    } else if (lastIdle) {
                        lastIdle = false
                        // pause
                        Intent(context, TVService::class.java).also {
                            it.action = TVService.PAUSE
                            startService(it)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getText(R.string.channel_name)
            val description = getText(R.string.channel_description).toString()
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val mChannel = NotificationChannel(CHANNEL_ID, name, importance)
            mChannel.description = description
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(mChannel)
        }
        Log.d(TAG, "PhoneStateReceiverService create")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "PhoneStateReceiverService destroy")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "PhoneStateReceiverService start")
        val action = intent?.action
        when (action) {
            START -> {
                if (!registered) {
                    Log.d(TAG, "Start foreground")
                    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Notification.Builder(this, CHANNEL_ID)
                    } else {
                        Notification.Builder(this)
                    }
                    val notification = builder.setContentTitle(getText(R.string.notification_title))
                        .setContentText(getText(R.string.notification_message))
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .build()
                    startForeground(NOTIFICATION_ID, notification)
                    Log.d(TAG, "Register receiver")
                    registerReceiver(phoneStateReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
                    registered = true
                }
            }
            STOP -> {
                if (registered) {
                    Log.d(TAG, "Stop foreground")
                    stopForeground(true)
                    Log.d(TAG, "Unregister receiver")
                    unregisterReceiver(phoneStateReceiver)
                    registered = false
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent?): IBinder? { return null }
}
