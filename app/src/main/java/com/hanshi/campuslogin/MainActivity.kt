package com.hanshi.campuslogin

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.hanshi.campuslogin.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: CredentialStore
    private lateinit var cm: ConnectivityManager

    /** 当前所有 WiFi 网络及其是否已通过联网验证，仅用于界面状态展示 */
    private val wifiNetworks = mutableMapOf<Network, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = CredentialStore(applicationContext)
        cm = getSystemService(ConnectivityManager::class.java)

        // targetSdk 35 强制全面屏，给内容留出状态栏/导航栏/键盘的空间
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        store.load()?.let { (account, password) ->
            binding.account.setText(account)
            binding.password.setText(password)
        }

        binding.loginButton.setOnClickListener { login() }
        binding.password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) login()
            false
        }
        binding.clearButton.setOnClickListener {
            store.clear()
            binding.account.text = null
            binding.password.text = null
            Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(request, wifiStatusCallback)
        renderWifiStatus()
    }

    override fun onStop() {
        super.onStop()
        runCatching { cm.unregisterNetworkCallback(wifiStatusCallback) }
        wifiNetworks.clear()
    }

    private val wifiStatusCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            runOnUiThread {
                wifiNetworks[network] = validated
                renderWifiStatus()
            }
        }

        override fun onLost(network: Network) {
            runOnUiThread {
                wifiNetworks.remove(network)
                renderWifiStatus()
            }
        }
    }

    private fun renderWifiStatus() {
        val (text, color) = when {
            wifiNetworks.isEmpty() -> R.string.wifi_none to R.color.status_bad
            wifiNetworks.values.any { it } -> R.string.wifi_validated to R.color.status_ok
            else -> R.string.wifi_unvalidated to R.color.status_warn
        }
        binding.wifiStatus.setText(text)
        binding.wifiStatus.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun login() {
        if (!binding.loginButton.isEnabled) return
        val account = binding.account.text?.toString()?.trim().orEmpty()
        val password = binding.password.text?.toString().orEmpty()
        if (account.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, R.string.error_empty, Toast.LENGTH_SHORT).show()
            return
        }
        store.save(account, password)

        setBusy(true)
        binding.log.text = null
        binding.result.text = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { performLogin(account, password) }
            appendLog("结果：${result.message}")
            showResult(result)
            setBusy(false)
        }
    }

    private suspend fun performLogin(account: String, password: String): LoginResult {
        appendLog("申请使用 WiFi 网络…")
        val session = WifiNetwork.acquire(applicationContext)
            ?: return LoginResult(LoginStatus.FAILED, "未检测到已连接的 WiFi，请先连接校园网 WiFi")

        session.use { wifi ->
            appendLog(
                if (wifi.isValidated) "WiFi 当前已可上网，仍向认证服务器确认…"
                else "WiFi 未认证，登录请求将强制通过 WiFi 发送",
            )
            val client = CampusLoginClient(
                http = wifi.httpClient(timeoutSeconds = 10),
                probeHttp = wifi.httpClient(timeoutSeconds = 3),
                log = ::appendLog,
            )
            val result = client.login(account, password, wifi.localNetInfo())
            if (result.status == LoginStatus.SUCCESS || result.status == LoginStatus.ALREADY_ONLINE) {
                wifi.reportLoggedIn()
            }
            return result
        }
    }

    private fun showResult(result: LoginResult) {
        val color = when (result.status) {
            LoginStatus.SUCCESS, LoginStatus.ALREADY_ONLINE -> R.color.status_ok
            LoginStatus.NOT_CAMPUS -> R.color.status_warn
            LoginStatus.FAILED -> R.color.status_bad
        }
        binding.result.text = result.message
        binding.result.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun setBusy(busy: Boolean) {
        binding.loginButton.isEnabled = !busy
        binding.loginButton.setText(if (busy) R.string.action_logging_in else R.string.action_login)
        binding.progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
    }

    private fun appendLog(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread { binding.log.append("[$time] $line\n") }
    }
}
