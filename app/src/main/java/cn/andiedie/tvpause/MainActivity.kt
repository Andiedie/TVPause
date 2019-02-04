package cn.andiedie.tvpause

import android.content.Context
import android.content.Intent
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val intent = Intent(this, Service::class.java)
        intent.action = Service.ACTION.Initial
        startService(intent)

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

        findViewById<CheckBox>(R.id.pauseOnCall).setOnClickListener{
            val checked = (it as CheckBox).isChecked
            val setting = getSharedPreferences(Const.SETTING_NAME, Context.MODE_PRIVATE)
            setting.edit().putBoolean(Const.PAUSE_ON_CALL, checked).apply()
        }
    }
}
