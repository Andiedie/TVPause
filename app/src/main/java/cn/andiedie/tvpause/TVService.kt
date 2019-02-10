package cn.andiedie.tvpause

import android.app.IntentService
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import com.rx2androidnetworking.Rx2AndroidNetworking
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import java.net.ConnectException
import java.net.Socket
import java.util.concurrent.TimeUnit
import android.R.id.message
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import kotlin.experimental.and


private const val TAG = "TVPause.TVService"
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


class TVService: IntentService(TAG) {
    lateinit var socket : Socket
    companion object ACTION {
        const val PAUSE = "PAUSE"
        const val RESUME = "RESUME"
    }
    private fun discovery() : Observable<NsdServiceInfo> {
        val result : Observable<NsdServiceInfo> =  if (checkWiFi()) {
            Observable.create { emitter ->
                val mNsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
                val mDiscoveryListener = object : NsdManager.DiscoveryListener {
                    val listen = this
                    override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                        Log.d(TAG, "ServiceFound: $serviceInfo")
                        mNsdManager.resolveService(serviceInfo, object: NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                                Log.e(TAG, "Resolve failed: $errorCode")
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                                Log.d(TAG, "Resolve Succeeded. $serviceInfo")
                                if (serviceInfo == null) return
                                emitter.onNext(serviceInfo)
                                mNsdManager.stopServiceDiscovery(listen)
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
                        emitter.onComplete()
                    }
                }
                mNsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener)
            }
        } else {
            Observable.error(Error("非 WiFi 网络"))
        }
        return result.timeout(5, TimeUnit.SECONDS)
    }

    private fun connect(serviceInfo: NsdServiceInfo) : Observable<Socket> {
        val result : Observable<Socket> = Observable.create {
            var socket : Socket?
            while (true) {
                try {
                    socket = Socket(serviceInfo.host, serviceInfo.port)
                    Log.d(TAG, "Socket connected: ${serviceInfo.host}:${serviceInfo.port}")
                    break
                } catch (err : ConnectException) {
                    Thread.sleep(1000)
                    Log.e(TAG, err.toString())
                    Log.d(TAG, "Retrying")
                }
            }

            if (socket == null) throw Error("连接小米电视失败")

            it.onNext(socket)
            it.onComplete()
        }
        return result.timeout(5, TimeUnit.SECONDS)
    }

    private fun checkWiFi() : Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            connectivityManager.activeNetworkInfo.type == ConnectivityManager.TYPE_WIFI
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
//            oStream.write(up)
        }
//        oStream.write(up)
        oStream.flush()
        Log.d(TAG, "Volume ${if(delta > 0) "up" else "down"} ${Math.abs(delta)}")
    }

    private fun md5(text: String): String {
        try {
            val instance: MessageDigest = MessageDigest.getInstance("MD5")
            val digest:ByteArray = instance.digest(text.toByteArray())
            val sb : StringBuffer = StringBuffer()
            for (b in digest) {
                //获取低八位有效值
                val i :Int = b.toInt() and 0xff
                //将整数转化为16进制
                var hexString = Integer.toHexString(i)
                if (hexString.length < 2) {
                    //如果是一位的话，补0
                    hexString = "0$hexString"
                }
                sb.append(hexString)
            }
            return sb.toString()

        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
        }

        return ""
    }

    override fun onHandleIntent(intent: Intent?) {
        val action = intent?.action
        val setting = getSharedPreferences(Const.SETTING_NAME, Context.MODE_PRIVATE)
        when (action) {
            PAUSE -> {
                Log.d(TAG, "PAUSE")
                discovery()
                    .flatMap { connect(it) }
                    .flatMap<Volume> {
                        socket = it
                        getVolume(it)
                    }
                    .subscribeOn(Schedulers.io())
                    .observeOn(Schedulers.io())
                    .subscribe({
                        val target = 0
                        setting.edit().putInt(Const.VOLUME_BACKUP, it.volum).apply()
                        Log.d(TAG, "target: $target current:${it.volum}")
                        pauseOrStop(socket)
                        setVolume(-2, socket)
//                        setVolume(target - it.volum, socket)
//                        socket.close()
                        Handler(Looper.getMainLooper()).post{
                            Toast.makeText(
                                applicationContext,
                                "暂停播放",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        stopSelf()
                    }, {
                        Log.e(TAG, it.message)
                    })
            }
            RESUME -> {
                Log.d(TAG, "RESUME")
                discovery()
                    .flatMap { connect(it) }
                    .flatMap<Volume> {
                        socket = it
                        getVolume(it)
                    }
                    .subscribeOn(Schedulers.io())
                    .observeOn(Schedulers.io())
                    .subscribe({
                        val target = setting.getInt(Const.VOLUME_BACKUP, 0)
                        Log.d(TAG, "target: $target current:${it.volum}")
                        pauseOrStop(socket)
                        setVolume(2, socket)
//                        setVolume(target - it.volum, socket)
//                        socket.close()
                        Handler(Looper.getMainLooper()).post{
                            Toast.makeText(
                                applicationContext,
                                "继续播放",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        stopSelf()
                    }, {
                        Log.e(TAG, it.message)
                    })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Destroy")
        if (::socket.isInitialized) {
            socket.close()
            Log.d(TAG, "Socket close")
        }
    }
}
