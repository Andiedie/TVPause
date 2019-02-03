package cn.andiedie.tvpause

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.util.Log
import io.reactivex.Observable
import java.net.Socket

private const val TAG = "TVPause.TVDiscovery"
private const val SERVICE_TYPE = "_rc._tcp"

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

    lateinit var mSocket : Socket
    object ACTION {
        const val Initial = "Initial"
        const val Stop = "Stop"
        const val Resume = "Resume"
    }
    private fun discovery() : Observable<NsdServiceInfo> {
        return Observable.create{
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

    private fun connect(serviceInfo: NsdServiceInfo) : Observable<Socket> {
        val host = serviceInfo.host
        val port = serviceInfo.port
        return Observable.create{
            it.onNext(Socket(host, port))
            Log.d(TAG, "Connected")
            it.onComplete()
        }
    }

    private fun getSocket() : Observable<Socket> {
        Log.d(TAG, "mSocket initialized: ${::mSocket.isInitialized}")
        return if (::mSocket.isInitialized) {
            Observable.just(mSocket)
        } else {
            discovery().flatMap { connect(it) }
        }
    }

    private fun stopOrResume(socket: Socket) {
        val oStream = socket.getOutputStream()
        val press = CONFIRM_BYTES
        val up = CONFIRM_BYTES.copyOf()
        up[0x13] = 0x01
        oStream.write(press)
        oStream.write(up)
        oStream.flush()
        Log.d(TAG, "stopOrResume")
    }

    private fun setVolume(socket: Socket, current: Int, target: Int) {
        if (current == target) { return }
        val oStream = socket.getOutputStream()
        val press = if (current < target) VOLUME_UP_BYTES else VOLUME_DOWN_BYTES
        val up = CONFIRM_BYTES.copyOf()
        up[0x13] = 0x01
        for (i in 1..(Math.abs(target - current))) {
            oStream.write(press)
            oStream.write(up)
        }
        oStream.flush()
        Log.d(TAG, "setVolume from $current to $target")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION.Initial -> {
                Log.d(TAG, "Initial")
                getSocket().subscribe { mSocket = it }
            }
            ACTION.Stop -> {
                Log.d(TAG, "Stop")
                getSocket().subscribe {
                    stopOrResume(it)
                    setVolume(it, 50, 0)
                }
            }
            ACTION.Resume -> {
                Log.d(TAG, "Resume")
                getSocket().subscribe {
                    stopOrResume(it)
                    setVolume(it, 0, 20)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        if (::mSocket.isInitialized) {
            mSocket.close()
            Log.d(TAG, "Socket close")
        }
        super.onDestroy()
    }

}
