package cn.andiedie.tvpause

import android.app.IntentService
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import io.reactivex.Observable
import java.net.Socket

private const val TAG = "TVPause.TVDiscovery"
private const val SERVICE_TYPE = "_rc._tcp"

class Service: IntentService("TVPauseService") {
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
            it.onComplete()
        }
    }

    private fun getSocket() : Observable<Socket> {
        return if (::mSocket.isInitialized) {
            Observable.just(mSocket)
        } else {
            discovery().flatMap { connect(it) }
        }
    }

    private fun send(socket: Socket) {
        val press = byteArrayOf(
            0x04, 0x00, 0x41, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x3a, 0x01, 0x00, 0x00, 0x00, 0x00, 0x02,
            0x00, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x42, 0x04, 0x00, 0x00, 0x00, 0x1c, 0x05, 0x00,
            0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x08, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0a, 0x00, 0x00, 0x00, 0x00, 0x0b,
            0x00, 0x00, 0x03, 0x01
        )
        val up = byteArrayOf(
            0x04, 0x00, 0x41, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x3a, 0x01, 0x00, 0x00, 0x00, 0x00, 0x02,
            0x00, 0x00, 0x00, 0x01, 0x03, 0x00, 0x00, 0x00, 0x42, 0x04, 0x00, 0x00, 0x00, 0x1c, 0x05, 0x00,
            0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x08, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0a, 0x00, 0x00, 0x00, 0x00, 0x0b,
            0x00, 0x00, 0x03, 0x01
        )
        val oStream = socket.getOutputStream()
        oStream.write(press)
        oStream.write(up)
        oStream.flush()
        Log.d(TAG, "Sent")
    }

    override fun onHandleIntent(intent: Intent?) {
        val action = intent?.action
        when (action) {
            ACTION.Initial -> {
                getSocket().doOnNext { mSocket = it }
            }
        }
    }

}
