package com.hanshi.campuslogin

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher

class CampusLoginClientTest {

    private lateinit var server: MockWebServer
    private lateinit var keyPair: KeyPair
    private lateinit var endpoints: Endpoints
    private val net = LocalNetInfo(ip = "10.20.30.40", mac = "aabbccddeeff", gateway = "192.168.2.33")

    // 可按用例调整的模拟服务器行为
    private var campusReachable = true
    private var postStatus = 302
    private var finalTitle = "认证成功页"
    private val submittedForms = mutableListOf<Map<String, String>>()

    @Before
    fun setUp() {
        keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.genKeyPair()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return when {
                    path == "/probe" ->
                        MockResponse().setResponseCode(if (campusReachable) 200 else 503)
                    path == "/cas/login" && request.method == "GET" ->
                        MockResponse()
                            .addHeader("Set-Cookie", "JSESSIONID=abc; Path=/")
                            .setBody("""<form><input type="hidden" name="execution" value="e1s1-token"/></form>""")
                    path == "/cas/jwt/publicKey" ->
                        MockResponse().setBody(pem(keyPair.public.encoded, "PUBLIC KEY"))
                    path == "/cas/login" && request.method == "POST" -> {
                        submittedForms += parseForm(request.body.readUtf8()) +
                            ("cookie" to request.getHeader("Cookie").orEmpty())
                        when (postStatus) {
                            302 -> MockResponse().setResponseCode(302)
                                .addHeader("Location", server.url("/portal?ticket=ST-1"))
                            else -> MockResponse().setResponseCode(postStatus)
                        }
                    }
                    path == "/portal" ->
                        MockResponse().setBody("<html><head><title>\n  $finalTitle\n</title></head></html>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        endpoints = Endpoints(
            casLogin = server.url("/cas/login").toString(),
            publicKey = server.url("/cas/jwt/publicKey").toString(),
            portal = "http://192.168.2.34:801/eportal/portal/cas/login",
            campusProbe = server.url("/probe").toString(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = CampusLoginClient(
        http = OkHttpClient.Builder().cookieJar(MemoryCookieJar()).build(),
        endpoints = endpoints,
    )

    @Test
    fun successfulLoginSubmitsEncryptedPasswordAndFollowsRedirect() {
        val result = client().login("202212345678", "p@ss 密码+/=", net)

        assertEquals(LoginResult(LoginStatus.SUCCESS, "校园网认证成功"), result)
        val form = submittedForms.single()
        assertEquals("202212345678", form["username"])
        assertEquals("e1s1-token", form["execution"])
        assertEquals("submit", form["_eventId"])
        assertTrue("CAS 会话 Cookie 应随表单提交", form["cookie"]!!.contains("JSESSIONID=abc"))

        val encrypted = form["password"]!!
        assertTrue(encrypted.startsWith("__RSA__"))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, keyPair.private) }
        val decrypted = cipher.doFinal(Base64.getDecoder().decode(encrypted.removePrefix("__RSA__")))
        assertEquals("p@ss 密码+/=", String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun alreadyOnline() {
        finalTitle = "信息页"
        assertEquals(LoginStatus.ALREADY_ONLINE, client().login("1", "2", net).status)
    }

    @Test
    fun wrongPasswordReturns401() {
        postStatus = 401
        assertEquals(LoginResult(LoginStatus.FAILED, "账号或密码错误"), client().login("1", "2", net))
    }

    @Test
    fun unknownTitleIsFailure() {
        finalTitle = "统一身份认证"
        assertEquals(LoginResult(LoginStatus.FAILED, "认证异常：统一身份认证"), client().login("1", "2", net))
    }

    @Test
    fun notCampusSkipsLogin() {
        campusReachable = false
        assertEquals(LoginStatus.NOT_CAMPUS, client().login("1", "2", net).status)
        assertTrue(submittedForms.isEmpty())
    }

    @Test
    fun loginUrlCarriesBase64StateLikeDesktopVersion() {
        val url = CampusLoginClient.buildLoginUrl(Endpoints(), net)
        assertEquals("hscas.hstc.edu.cn", url.host)
        assertEquals("/cas/login", url.encodedPath)

        // 与 Python quote(service, safe="") 一致：service 整体百分号编码，Base64 中的 + / = 不能裸露
        val rawService = url.encodedQuery!!.removePrefix("service=")
        assertTrue(rawService.none { it in "/:?=+" })

        val service = URLDecoder.decode(rawService, "UTF-8")
        val prefix = "http://192.168.2.34:801/eportal/portal/cas/login?state="
        assertTrue(service.startsWith(prefix))
        val state = String(Base64.getDecoder().decode(service.removePrefix(prefix)), Charsets.UTF_8)
        assertEquals("1|0|10.20.30.40||aabbccddeeff||192.168.2.33|CORE-RG-N18012|", state)

        val unknownGateway = CampusLoginClient.buildLoginUrl(Endpoints(), net.copy(gateway = "10.1.1.1"))
        val unknownState = String(
            Base64.getDecoder().decode(unknownGateway.queryParameter("service")!!.substringAfter("state=")),
            Charsets.UTF_8,
        )
        assertTrue(unknownState.endsWith("|10.1.1.1|UNKNOWN-AP|"))
    }

    @Test
    fun extractExecutionInEitherAttributeOrder() {
        assertEquals("abc", CampusLoginClient.extractExecution("""<input name="execution" type="hidden" value="abc">"""))
        assertEquals("xyz", CampusLoginClient.extractExecution("""<input value="xyz" type="hidden" name="execution">"""))
        assertNull(CampusLoginClient.extractExecution("<input name=\"lt\" value=\"1\">"))
    }

    @Test
    fun parsesPkcs1AndBarePublicKeys() {
        val pub = keyPair.public as RSAPublicKey
        val pkcs1 = der(0x30, der(0x02, pub.modulus.toByteArray()) + der(0x02, pub.publicExponent.toByteArray()))

        for (text in listOf(
            pem(pkcs1, "RSA PUBLIC KEY"),
            Base64.getEncoder().encodeToString(pub.encoded),
            pem(pub.encoded, "PUBLIC KEY").replace("\n", "\r\n"),
        )) {
            val parsed = RsaUtil.parsePublicKey(text) as RSAPublicKey
            assertEquals(pub.modulus, parsed.modulus)
            assertEquals(BigInteger.valueOf(65537), parsed.publicExponent)
        }
    }

    private fun pem(der: ByteArray, type: String) =
        "-----BEGIN $type-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
            "\n-----END $type-----\n"

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val len = content.size
        val header = when {
            len < 0x80 -> byteArrayOf(tag.toByte(), len.toByte())
            len < 0x100 -> byteArrayOf(tag.toByte(), 0x81.toByte(), len.toByte())
            else -> byteArrayOf(tag.toByte(), 0x82.toByte(), (len shr 8).toByte(), len.toByte())
        }
        return header + content
    }

    private fun parseForm(body: String): Map<String, String> = body.split("&").associate {
        val (k, v) = it.split("=", limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }
}
