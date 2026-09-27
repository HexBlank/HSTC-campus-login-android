package com.hanshi.campuslogin

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 解决「连上校园网 WiFi 但未认证时，系统把流量走移动数据」的问题。
 *
 * Android 发现 WiFi 无法上网（未通过联网验证）时，会把默认网络保持/切换到移动数据，
 * 普通 HTTP 请求就会从流量出去，永远到不了校内认证服务器。这里的做法是：
 *
 * 1. 用 requestNetwork 显式申请 TRANSPORT_WIFI 网络（不要求 VALIDATED，未认证的 WiFi 也能拿到），
 *    申请期间系统也不会因为「无法上网」而断开/闲置这张 WiFi；
 * 2. OkHttp 的 socketFactory 用 Network.socketFactory —— 每个 TCP 连接都绑定到 WiFi 网卡；
 * 3. DNS 用 Network.getAllByName —— 域名走 WiFi 的 DNS（校内 DNS），而不是流量的 DNS；
 * 4. 额外 bindProcessToNetwork 把整个进程临时绑定到 WiFi 兜底，结束后恢复；
 * 5. 显式 Proxy.NO_PROXY，避免系统代理把请求带走。
 */
object WifiNetwork {

    /** 申请 WiFi 网络，[timeoutMs] 内没有已连接的 WiFi 则返回 null */
    suspend fun acquire(context: Context, timeoutMs: Int = 5_000): WifiSession? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        return suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (done.compareAndSet(false, true)) cont.resume(WifiSession(cm, network, this))
                }

                override fun onUnavailable() {
                    if (done.compareAndSet(false, true)) {
                        runCatching { cm.unregisterNetworkCallback(this) }
                        cont.resume(null)
                    }
                }
            }
            cm.requestNetwork(request, callback, timeoutMs)
            cont.invokeOnCancellation {
                if (done.compareAndSet(false, true)) runCatching { cm.unregisterNetworkCallback(callback) }
            }
        }
    }
}

/** 一次登录期间持有的 WiFi 网络；用完必须 close() 释放网络申请并解除进程绑定 */
class WifiSession internal constructor(
    private val cm: ConnectivityManager,
    val network: Network,
    private val callback: ConnectivityManager.NetworkCallback,
) : Closeable {

    private val previousBinding: Network? = cm.boundNetworkForProcess

    init {
        cm.bindProcessToNetwork(network)
    }

    private val cookieJar = MemoryCookieJar()

    private val linkProperties: LinkProperties? get() = cm.getLinkProperties(network)

    val isValidated: Boolean
        get() = cm.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

    /** WiFi 网卡上的 IPv4、MAC 和默认网关 */
    fun localNetInfo(): LocalNetInfo {
        val lp = linkProperties
        val ip = lp?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
        val gateway = lp?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
        return LocalNetInfo(
            ip = ip ?: "0.0.0.0",
            mac = macOf(lp?.interfaceName) ?: "000000000000",
            gateway = gateway ?: "0.0.0.0",
        )
    }

    /** Android 11+ 普通应用读不到 MAC（返回 null），此时由调用方用占位值 */
    private fun macOf(interfaceName: String?): String? = runCatching {
        NetworkInterface.getByName(interfaceName ?: return null)?.hardwareAddress
            ?.takeIf { bytes -> bytes.size == 6 && bytes.any { it.toInt() != 0 } }
            ?.joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** 所有连接、DNS 均绑定到这张 WiFi 的 HTTP 客户端（同一客户端共享 Cookie，保持 CAS 会话） */
    fun httpClient(timeoutSeconds: Long): OkHttpClient = OkHttpClient.Builder()
        .socketFactory(network.socketFactory)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
        })
        .proxy(Proxy.NO_PROXY)
        .cookieJar(cookieJar)
        .followRedirects(true)
        .followSslRedirects(true) // CAS 登录成功后会从 HTTPS 跳回 HTTP 门户
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds * 2, TimeUnit.SECONDS)
        .build()

    /** 认证成功后通知系统重新做联网验证，WiFi 会更快变成「可上网」并成为默认网络 */
    fun reportLoggedIn() {
        runCatching { cm.reportNetworkConnectivity(network, true) }
    }

    override fun close() {
        runCatching { cm.bindProcessToNetwork(previousBinding) }
        runCatching { cm.unregisterNetworkCallback(callback) }
    }
}
