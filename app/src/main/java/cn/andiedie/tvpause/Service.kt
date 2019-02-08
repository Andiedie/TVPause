package cn.andiedie.tvpause

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.util.Log
import com.androidnetworking.AndroidNetworking
import com.google.gson.Gson
import com.rx2androidnetworking.Rx2AndroidNetworking
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import org.greenrobot.eventbus.EventBus
import java.io.IOError
import java.io.IOException
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

    lateinit var discoveryResult: Observable<NsdServiceInfo>
    private var mSocket : Socket? = null
    private var volumeBackup = 0

    object ACTION {
        const val Initial = "Initial"
        const val Pause = "Pause"
        const val Resume = "Resume"
    }
    private fun discovery() : Observable<NsdServiceInfo> {
        if (!::discoveryResult.isInitialized) {
            discoveryResult = Observable.create{
                val mNsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
                val mDiscoveryListener = object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                        val mDiscoveryListener = this
                        Log.d(TAG, "ServiceFound: $serviceInfo")
                        mNsdManager.resolveService(serviceInfo, object: NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                                Log.e(TAG, "Resolve failed: $errorCode")
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                                Log.d(TAG, "Resolve Succeeded. $serviceInfo")
                                mNsdManager.stopServiceDiscovery(mDiscoveryListener)
                                if (serviceInfo !== null) {
                                    it.onNext(serviceInfo)
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
                        it.onComplete()
                    }
                }
                mNsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener)
            }
        }
        return discoveryResult
    }

    private fun connect() : Observable<Socket> {
        return if (mSocket != null) {
            Observable.just(mSocket)
        } else {
            discovery().flatMap<Socket> {serviceInfo ->
                Observable.create{
                    val host = serviceInfo.host
                    val port = serviceInfo.port
                    val socket = Socket(host, port)
                    it.onNext(socket)
                    mSocket = socket
                    EventBus.getDefault().post(ConnectedEvent(true))
                    Log.d(TAG, "Socket connected")
                    val onClose: Observable<Boolean> = Observable.create{emitter ->
                        try {
                            var bytes : Int
                            do {
                                bytes = socket.getInputStream().read()
                                Log.v(TAG, "Socket received $bytes")
                            }
                            while (bytes != -1)
                        } catch (err: IOException) {
                            Log.e(TAG, "Socket exception: ${err.message}")
                        }
                        Log.d(TAG, "Socket closed")
                        emitter.onNext(true)
                        emitter.onComplete()
                    }
                    Log.d(TAG, "onClose defined")
                    onClose
                        .observeOn(Schedulers.io())
                        .subscribeOn(Schedulers.io())
                        .subscribe{
                            mSocket = null
                            Log.d(TAG, "Socket disconnected")
                            EventBus.getDefault().post(ConnectedEvent(false))
                        }
                    Log.d(TAG, "onClose subscribe")
                    it.onComplete()
                }
            }
        }
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
        val action = intent?.action
        when (action) {
            ACTION.Initial -> {
                Log.d(TAG, "Initial")
                AndroidNetworking.initialize(this)
                connect().subscribe {}
            }
            ACTION.Pause -> {
                Log.d(TAG, "Pause")
                connect().subscribe {socket ->
                    val target = 0
                    getVolume(socket).subscribe {
                        pauseOrStop(socket)
                        volumeBackup = it.volum
                        Log.d(TAG, "target: $target current:${it.volum}")
                        setVolume(target - it.volum, socket)
                    }
                }
            }
            ACTION.Resume -> {
                Log.d(TAG, "Resume")
                connect().subscribe {socket ->
                    val target = volumeBackup
                    getVolume(socket).subscribe {
                        pauseOrStop(socket)
                        Log.d(TAG, "target: $target current:${it.volum}")
                        setVolume(target - it.volum, socket)
                    }
                }

            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        mSocket?.close()
        Log.d(TAG, "Socket close")
    }

}
