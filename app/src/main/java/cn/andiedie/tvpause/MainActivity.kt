package cn.andiedie.tvpause

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView

private const val PERMISSIONS_REQUEST = 852
private const val TAG = "TVPause.MainActivity"

class MainActivity : AppCompatActivity() {
    private var permission = false
    private lateinit var statusView : TextView
    private lateinit var checkBox: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)

        findViewById<Button>(R.id.pause).setOnClickListener {
            Intent(this, TVService::class.java).also {
                it.action = TVService.PAUSE
                startService(it)
            }
        }

        findViewById<Button>(R.id.resume).setOnClickListener {
            Intent(this, TVService::class.java).also {
                it.action = TVService.RESUME
                startService(it)
            }
        }

        checkBox = findViewById<CheckBox>(R.id.pauseOnCall)
        val setting = getSharedPreferences(Const.SETTING_NAME, Context.MODE_PRIVATE)
        checkBox.isChecked = setting.getBoolean(Const.PAUSE_ON_CALL, false)

        checkBox.setOnClickListener{
            val pauseOnCall = (it as CheckBox).isChecked
            setting.edit().putBoolean(Const.PAUSE_ON_CALL, pauseOnCall).apply()
            if (pauseOnCall) {
                startPhoneStateReceiverService()
            } else {
                stopPhoneStateReceiverService()
            }
        }

        val permissions = mutableListOf<String>(
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissions += Manifest.permission.FOREGROUND_SERVICE
        }
        if (!hasPermissions(permissions)) {
            Log.d(TAG, "Permissions missing")
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSIONS_REQUEST)
        } else {
            Log.d(TAG, "Permissions granted")
            permission = true
        }

        updateStatus()
    }

    private fun hasPermissions(permissions: List<String>): Boolean {
        for (permission in permissions) {
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.isEmpty()) return
        when (requestCode) {
            PERMISSIONS_REQUEST -> {
                var flag = true
                for (result in grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        flag = false
                        break
                    }
                }
                permission = if (flag) {
                    Log.d(TAG, "Request permissions success")
                    true
                } else {
                    Log.d(TAG, "Request permissions failed")
                    false
                }
            }
        }
        updateStatus()

    }

    private fun startPhoneStateReceiverService() {
        Intent(this, PhoneStateReceiverService::class.java).also {intent ->
            intent.action = PhoneStateReceiverService.START
            startService(intent)
        }
    }

    private fun stopPhoneStateReceiverService () {
        Intent(this, PhoneStateReceiverService::class.java).also {intent ->
            intent.action = PhoneStateReceiverService.STOP
            startService(intent)
        }
    }

    private fun updateStatus() {
        statusView.text = if (permission) "已授权" else "未授权"
        val color : Int = if (permission) {
            R.color.colorSuccess
        } else {
            R.color.colorFail
        }
        checkBox.isEnabled = permission
        statusView.setBackgroundResource(color)

        if (checkBox.isChecked) {
            startPhoneStateReceiverService()
        }
    }

}
