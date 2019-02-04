package cn.andiedie.tvpause

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView

private const val PERMISSIONS_REQUEST_READ_PHONE_STATE = 123
private const val TAG = "TVPause.MainActivity"

class MainActivity : AppCompatActivity() {
    private var permission = false
    private var link = false
    private var pauseOnCall = false
    private lateinit var statusView : TextView
    private var receiver: PhoneStateReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val intent = Intent(this, Service::class.java)
        intent.action = Service.ACTION.Initial
        startService(intent)

        statusView = findViewById(R.id.status)

        findViewById<Button>(R.id.pause).setOnClickListener {
            Intent(this, Service::class.java)
            intent.action = Service.ACTION.Pause
            startService(intent)
        }

        findViewById<Button>(R.id.resume).setOnClickListener {
            Intent(this, Service::class.java)
            intent.action = Service.ACTION.Resume
            startService(intent)
        }

        val checkBox = findViewById<CheckBox>(R.id.pauseOnCall)
        pauseOnCall = getSharedPreferences(Const.SETTING_NAME, Context.MODE_PRIVATE).getBoolean(Const.PAUSE_ON_CALL, false)
        checkBox.isChecked = pauseOnCall

        checkBox.setOnClickListener{
            pauseOnCall = (it as CheckBox).isChecked
            val setting = getSharedPreferences(Const.SETTING_NAME, Context.MODE_PRIVATE)
            setting.edit().putBoolean(Const.PAUSE_ON_CALL, pauseOnCall).apply()
            if (pauseOnCall) {
                registerPhoneStateReceiver()
            } else {
                unregisterPhoneStateReceiver()
            }
        }
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "No phone state permission")
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.READ_PHONE_STATE),
                PERMISSIONS_REQUEST_READ_PHONE_STATE)
        } else {
            Log.d(TAG, "Phone state permission granted")
            permission = true
            if (pauseOnCall) {
                registerPhoneStateReceiver()
            }
        }

        updateStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            PERMISSIONS_REQUEST_READ_PHONE_STATE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Request Phone state permission success")
                    permission = true
                    updateStatus()
                    registerPhoneStateReceiver()
                } else {
                    Log.d(TAG, "Request phone state permission failed")
                    permission = false
                    updateStatus()
                }
            }
        }
    }

    private fun registerPhoneStateReceiver() {
        if (receiver == null) {
            Log.d(TAG, "Register PhoneStateReceive")
            receiver = PhoneStateReceiver()
            IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                .also { registerReceiver(receiver, it) }
        }
    }

    private fun unregisterPhoneStateReceiver() {
        if (receiver != null) {
            Log.d(TAG, "Unregister PhoneStateReceive")

            unregisterReceiver(receiver)
            receiver = null
        }
    }

    private fun updateStatus() {
        statusView.text = resources.getString(R.string.status,
            if (permission) "已授权" else "未授权",
            if (link) "已连接" else "未连接")
        val color : Int = if (pauseOnCall) {
            if (permission && link) {
                R.color.colorSuccess
            } else {
                R.color.colorFail
            }
        } else {
            if (link) {
                R.color.colorSuccess
            } else {
                R.color.colorFail
            }
        }
        statusView.setBackgroundResource(color)
    }
}
