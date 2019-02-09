package cn.andiedie.tvpause

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import com.androidnetworking.AndroidNetworking
import com.google.gson.Gson
import com.rx2androidnetworking.Rx2AndroidNetworking
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import org.greenrobot.eventbus.EventBus
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket

private const val TAG = "TVPause.Service"
private const val SERVICE_TYPE = "_rc._tcp"
private const val HTTP_PORT = 6095

private val CONFIRM_BYTES = byteArrayOf(
    0x04, 0x00, 0x41, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x3a, 0x01, 0x00, 0x00, 0x00, 0x00, 0x02,
    0x00, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x42, 0x04, 0x00, 0x00, 0x00, 0x1c, 0x05, 0x00,
    0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x08, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0a, 0x00, 0x00, 0x00, 0x00, 0x0b,
    0x00, 0x00, 0x03, 0x01
)

private val VOLUME_UP_BYTES = byteArrayOf(
    0x04, 0x00, 0x41, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x3a, 0x01, 0x00, 0x00, 0x00, 0x00, 0x02,
    0x00, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x18, 0x04, 0x00, 0x00, 0x00, 0x73, 0x05, 0x00,
    0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x08, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0a, 0x00, 0x00, 0x00, 0x00, 0x0b,
    0x00, 0x00, 0x03, 0x01
)

private val VOLUME_DOWN_BYTES = byteArrayOf(
    0x04, 0x00, 0x41, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x3a, 0x01, 0x00, 0x00, 0x00, 0x00, 0x02,
    0x00, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x19, 0x04, 0x00, 0x00, 0x00, 0x72, 0x05, 0x00,
    0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x08, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0a, 0x00, 0x00, 0x00, 0x00, 0x0b,
    0x00, 0x00, 0x03, 0x01
)


class Service: android.app.Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private var mSocket : Socket? = null
    private var volumeBackup = 0
    private var host : InetAddress? = null
    private var port : Int? = null
    private var discoveryRunning = false
    private var compositeDisposable = CompositeDisposable()

    object ACTION {
        const val Initial = "Initial"
        const val Pause = "Pause"
        const val Resume = "Resume"
    }
    private fun discovery() {
        if (discoveryRunning) return
        discoveryRunning = true
        val mNsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        val mDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                Log.d(TAG, "ServiceFound: $serviceInfo")
                mNsdManager.resolveService(serviceInfo, object: NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        Log.e(TAG, "Resolve failed: $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                        Log.d(TAG, "Resolve Succeeded. $serviceInfo")
                        if (serviceInfo == null) return
                        if (host != serviceInfo.host || port != serviceInfo.port || mSocket == null) {
                            host = serviceInfo.host
                            port = serviceInfo.port
                            connect()
                        }
                    }

                })
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Stop DNS-SD discovery failed: Error code:$errorCode")
                mNsdManager.stopServiceDiscovery(this)
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Start DNS-SD discovery failed: Error code:$errorCode")
                mNsdManager.stopServiceDiscovery(this)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                Log.e(TAG, "DNS-SD service lost: $serviceInfo")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d(TAG, "DNS-SD discovery started")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d(TAG, "DNS-SD discovery stopped: $serviceType")
            }
        }
        mNsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener)
    }

    private fun connect() {
        val p = port
        val h = host
        if (h == null || p == null) return
        var socket : Socket? = null
        var retryTimes = 3
        while (retryTimes-- != 0) {
            try {
                socket = Socket(h, p)
                Log.d(TAG, "Socket connected: $host:$port")
                break
            } catch (err : ConnectException) {
                Thread.sleep(1000)
                Log.e(TAG, err.toString())
            }
        }
        if (socket == null) return

        mSocket = socket
        val onClose: Observable<Boolean> = Observable.create{ emitter ->
            try {
                var bytes : Int
                do {
                    bytes = socket.getInputStream().read()
                }
                while (bytes != -1)
                Log.e(TAG, "Socket received -1")
                emitter.onNext(true)
            } catch (err: IOException) {
                Log.e(TAG, "Socket exception: ${err.message}")
                emitter.onNext(false)
            }
            Log.d(TAG, "Socket closed")

            emitter.onComplete()
        }
        val compositeDisposable = CompositeDisposable()
        compositeDisposable.add(
            onClose
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .subscribe {
                    mSocket?.close()
                    mSocket = null
                    Log.d(TAG, "Socket disconnected")
                    EventBus.getDefault().post(ConnectedEvent(false))
                    if (it) connect()
                }
        )

        EventBus.getDefault().post(ConnectedEvent(true))
    }

    private fun getVolume(socket: Socket) : Observable<Volume> {
        return Rx2AndroidNetworking
            .get("http://${socket.inetAddress.hostAddress}:$HTTP_PORT/general")
            .addQueryParameter("action", "getVolum")
            .build()
            .getObjectObservable(APIVolume::class.java)
            .subscribeOn(Schedulers.io())
            .map { Gson().fromJson(it.data, Volume::class.java) }
    }

    private fun pauseOrStop(socket: Socket) {
        val oStream = socket.getOutputStream()
        val press = CONFIRM_BYTES
        val up = CONFIRM_BYTES.copyOf()
        up[0x13] = 0x01
        oStream.write(press)
        oStream.write(up)
        oStream.flush()
        Log.d(TAG, "stopOrResume")
    }

    private fun setVolume(delta: Int, socket: Socket) {
        if (delta == 0) { return }
        val oStream = socket.getOutputStream()
        val press = if (delta > 0) VOLUME_UP_BYTES else VOLUME_DOWN_BYTES
        val up = CONFIRM_BYTES.copyOf()
        up[0x13] = 0x01
        for (i in 1..(Math.abs(delta))) {
            oStream.write(press)
            oStream.write(up)
        }
        oStream.flush()
        Log.d(TAG, "Volume ${if(delta > 0) "up" else "down"} ${Math.abs(delta)}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AndroidNetworking.initialize(this)
        val action = intent?.action
        when (action) {
            ACTION.Initial -> {
                Log.d(TAG, "Initial")
                discovery()
            }
            ACTION.Pause -> {
                Log.d(TAG, "Pause")
                val socket = mSocket
                if (socket != null) {
                    val target = 0
                    compositeDisposable.add(
                        getVolume(socket).subscribe {
                            pauseOrStop(socket)
                            volumeBackup = it.volum
                            Log.d(TAG, "target: $target current:${it.volum}")
                            setVolume(target - it.volum, socket)
                        }
                    )
                    Toast.makeText(this, "暂停播放", Toast.LENGTH_LONG).show()
                }
            }
            ACTION.Resume -> {
                Log.d(TAG, "Resume")
                val socket = mSocket
                if (socket != null) {
                    val target = volumeBackup
                    compositeDisposable.add(
                        getVolume(socket).subscribe {
                            pauseOrStop(socket)
                            Log.d(TAG, "target: $target current:${it.volum}")
                            setVolume(target - it.volum, socket)
                        }
                    )
                    Toast.makeText(this, "暂停播放", Toast.LENGTH_LONG).show()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        mSocket?.close()
        compositeDisposable.dispose()
        Log.d(TAG, "Socket close")
    }

}
