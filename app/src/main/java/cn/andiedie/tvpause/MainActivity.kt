package cn.andiedie.tvpause

import android.content.Intent
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val intent = Intent(this, Service::class.java)
        intent.action = Service.ACTION.Initial
        startService(intent)

        findViewById<Button>(R.id.stop).setOnClickListener {
            Intent(this, Service::class.java)
            intent.action = Service.ACTION.Stop
            startService(intent)
        }

        findViewById<Button>(R.id.resume).setOnClickListener {
            Intent(this, Service::class.java)
            intent.action = Service.ACTION.Resume
            startService(intent)
        }
    }
}
