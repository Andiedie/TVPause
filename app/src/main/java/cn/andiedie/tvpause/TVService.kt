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
import io.reactivex.schedulers.Schedulers
import java.net.ConnectException
import java.net.Socket
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper
import com.litesuits.common.utils.HexUtil
import com.litesuits.common.utils.MD5Util
import io.reactivex.functions.BiFunction
import java.net.InetAddress


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

class TVService: IntentService(TAG) {
    companion object ACTION {
        const val PAUSE = "PAUSE"
        const val RESUME = "RESUME"
    }
    private fun discovery() : Observable<NsdServiceInfo> {
        return if (checkWiFi()) {
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
    }

    private fun connect(serviceInfo: NsdServiceInfo) : Observable<Socket> {
        return Observable.create {
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

    private fun getVolume(address: InetAddress) : Observable<Volume> {
        return Rx2AndroidNetworking
            .get("http://${address.hostAddress}:$HTTP_PORT/general")
            .addQueryParameter("action", "getVolum")
            .build()
            .getObjectObservable(APIGetVolume::class.java)
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

    private fun setVolume(address: InetAddress, key: String, target: Int) : Observable<Boolean>  {
        Log.d(TAG, "Volume set to $target")
        val volume = target.toString()
        val now = System.currentTimeMillis().toString()
        val sign = HexUtil.encodeHexStr(MD5Util.md5("mitvsignsalt$volume$key${now.substring(now.length-5)}"))
        return Rx2AndroidNetworking
            .get("http://${address.hostAddress}:$HTTP_PORT/general")
            .addQueryParameter("action", "setVolum")
            .addQueryParameter("volum", volume)
            .addQueryParameter("ts", now)
            .addQueryParameter("sign", sign)
            .build()
            .getObjectObservable(APISetVolume::class.java)
            .map {
                if (it.request_result != 200) {
                    throw Error("Fail to set volume")
                }
                it.request_result == 200
            }

    }

    override fun onHandleIntent(intent: Intent?) {
        val action = intent?.action
        val setting = getSharedPreferences(Const.SETTING_NAME, Context.MODE_PRIVATE)
        when (action) {
            PAUSE -> {
                Log.d(TAG, "PAUSE")
                lateinit var info: NsdServiceInfo
                discovery()
                    .flatMap {
                        info = it
                        connect(it)
                    }
                    .flatMap {
                        pauseOrStop(it)
                        it.close()
                        getVolume(info.host)
                    }
                    .flatMap {
                        val bytes = info.attributes["mac"] ?: throw Error("Can not find mac")
                        val key = String(bytes)
                        setting.edit().putInt(Const.VOLUME_BACKUP, it.volum).apply()
                        Log.d(TAG, "Set volume to 0")
                        setVolume(info.host, key, 0)
                    }
                    .timeout(5, TimeUnit.SECONDS)
                    .subscribeOn(Schedulers.io())
                    .subscribe({
                        Handler(Looper.getMainLooper()).post{
                            Toast.makeText(
                                applicationContext,
                                "暂停播放",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }, {
                        Log.e(TAG, it.message)
                    })
            }
            RESUME -> {
                Log.d(TAG, "RESUME")
                lateinit var info: NsdServiceInfo
                discovery()
                    .flatMap {
                        info = it
                        connect(it)
                    }
                    .flatMap {
                        pauseOrStop(it)
                        it.close()
                        val bytes = info.attributes["mac"] ?: throw Error("Can not find mac")
                        val key = String(bytes)
                        val target = setting.getInt(Const.VOLUME_BACKUP, 0)
                        Log.d(TAG, "Set volume to $target")
                        setVolume(info.host, key, target)
                    }
                    .timeout(5, TimeUnit.SECONDS)
                    .subscribeOn(Schedulers.io())
                    .subscribe({
                        Handler(Looper.getMainLooper()).post{
                            Toast.makeText(
                                applicationContext,
                                "继续播放",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }, {
                        Log.e(TAG, it.message)
                    })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Destroy")
    }
}
