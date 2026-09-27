package com.hanshi.campuslogin

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.Base64

/** 登录结果 */
enum class LoginStatus { SUCCESS, ALREADY_ONLINE, NOT_CAMPUS, FAILED }

data class LoginResult(val status: LoginStatus, val message: String)

/** 本机在 WiFi 网络上的信息，用于拼接门户的 state 参数 */
data class LocalNetInfo(val ip: String, val mac: String, val gateway: String)

/** 认证相关地址，默认值即韩师校园网；单元测试时替换为本地模拟服务器 */
data class Endpoints(
    val casLogin: String = "https://hscas.hstc.edu.cn/cas/login",
    val publicKey: String = "https://hscas.hstc.edu.cn/cas/jwt/publicKey",
    val portal: String = "http://192.168.2.34:801/eportal/portal/cas/login",
    val campusProbe: String = "http://rz.hstc.edu.cn/",
)

/**
 * 韩师 CAS 认证流程（与电脑版 login_core.py 一致）：
 * 检测校园网 → 拼登录地址 → 取 execution → 取公钥 RSA 加密密码 → 提交表单 → 按返回页标题判定结果。
 *
 * 本类不关心请求走哪张网卡——调用方传入的 [http] 必须已经绑定到 WiFi 网络（见 WifiNetworkBinder）。
 * [probeHttp] 用于校园网检测，超时更短。
 */
class CampusLoginClient(
    private val http: OkHttpClient,
    private val probeHttp: OkHttpClient = http,
    private val endpoints: Endpoints = Endpoints(),
    private val log: (String) -> Unit = {},
) {

    fun isCampusNetwork(): Boolean = try {
        probeHttp.newCall(Request.Builder().url(endpoints.campusProbe).header("User-Agent", USER_AGENT).build())
            .execute().use { it.code == 200 }
    } catch (e: IOException) {
        false
    }

    fun login(account: String, password: String, net: LocalNetInfo): LoginResult {
        log("检测校园网环境：${endpoints.campusProbe}")
        if (!isCampusNetwork()) {
            return LoginResult(LoginStatus.NOT_CAMPUS, "当前 WiFi 不是校园网（校内认证地址不可达）")
        }

        return try {
            val loginUrl = buildLoginUrl(endpoints, net)
            log("本机 IP=${net.ip} MAC=${net.mac} 网关=${net.gateway}")

            log("打开 CAS 登录页…")
            val page = get(loginUrl)
            val execution = extractExecution(page)
                ?: return LoginResult(LoginStatus.FAILED, "登录页解析失败（未找到 execution 标识）")

            log("获取 CAS 公钥并加密密码…")
            val encryptedPassword = RsaUtil.encryptPassword(password, get(endpoints.publicKey.toHttpUrl()).trim())

            log("提交登录表单…")
            val form = FormBody.Builder()
                .add("username", account)
                .add("password", encryptedPassword)
                .add("currentMenu", "1")
                .add("_eventId", "submit")
                .add("submit", "Login1")
                .add("failN", "0")
                .add("execution", execution)
                .build()
            val request = Request.Builder().url(loginUrl).header("User-Agent", USER_AGENT).post(form).build()
            http.newCall(request).execute().use { resp ->
                if (resp.code == 401) return LoginResult(LoginStatus.FAILED, "账号或密码错误")
                judgeByTitle(resp.body?.string().orEmpty())
            }
        } catch (e: UnknownHostException) {
            LoginResult(
                LoginStatus.FAILED,
                "域名解析失败：${e.message}。若开启了「私人 DNS」，请在系统设置中改为「自动」或「关闭」后重试",
            )
        } catch (e: InterruptedIOException) {
            LoginResult(LoginStatus.FAILED, "网络请求超时，请确认已连接校园网 WiFi 后重试")
        } catch (e: IOException) {
            LoginResult(LoginStatus.FAILED, "网络请求失败：${e.message}")
        } catch (e: Exception) {
            LoginResult(LoginStatus.FAILED, "未知错误：${e.message}")
        }
    }

    private fun get(url: HttpUrl): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}：${url.host}")
            return resp.body?.string().orEmpty()
        }
    }

    companion object {
        // 与电脑版保持一致的桌面 UA，服务器按此返回相同页面，标题判定规则才能沿用
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // 网关 → 接入点名称（沿用电脑版配置）
        private val ACCESS_POINTS = mapOf(
            "192.168.2.33" to "CORE-RG-N18012",
            "10.0.0.1" to "CORE-ROUTER-2",
            "172.16.0.1" to "BACKUP-AP",
        )

        /** state 串：`1|0|IP||MAC||网关|接入点|`，Base64 后作为门户参数，门户地址再作为 CAS 的 service 参数 */
        fun buildLoginUrl(endpoints: Endpoints, net: LocalNetInfo): HttpUrl {
            val ap = ACCESS_POINTS[net.gateway] ?: "UNKNOWN-AP"
            val state = "1|0|${net.ip}||${net.mac}||${net.gateway}|$ap|"
            val stateB64 = Base64.getEncoder().encodeToString(state.toByteArray(Charsets.UTF_8))
            val service = "${endpoints.portal}?state=$stateB64"
            return endpoints.casLogin.toHttpUrl().newBuilder()
                .addQueryParameter("service", service)
                .build()
        }

        private val EXECUTION_NAME_FIRST = Regex("""name="execution"[^>]*value="([^"]+)"""")
        private val EXECUTION_VALUE_FIRST = Regex("""value="([^"]+)"[^>]*name="execution"""")
        private val TITLE = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)

        fun extractExecution(html: String): String? =
            (EXECUTION_NAME_FIRST.find(html) ?: EXECUTION_VALUE_FIRST.find(html))?.groupValues?.get(1)

        fun judgeByTitle(html: String): LoginResult {
            val title = TITLE.find(html)?.groupValues?.get(1)?.trim()
                ?: return LoginResult(LoginStatus.FAILED, "服务器响应异常（未找到页面标题）")
            return when (title) {
                "认证成功页" -> LoginResult(LoginStatus.SUCCESS, "校园网认证成功")
                "信息页" -> LoginResult(LoginStatus.ALREADY_ONLINE, "已经在线，无需重复登录")
                else -> LoginResult(LoginStatus.FAILED, "认证异常：$title")
            }
        }
    }
}
