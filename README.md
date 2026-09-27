# 韩师校园网登录器（安卓版）

韩师校园网一键登录 App，Kotlin 编写。连上校园网 WiFi 后打开 App，点一下「登录」即可完成认证。

- 账号密码输入一次后自动记住（Android Keystore 加密保存）
- 已保存账号时，打开 App 即自动登录，无需再点按钮
- 不常驻后台、不在后台静默登录，只在打开 App 或点击「登录」时认证
- **登录请求强制通过 WiFi 发送**，不会因为 WiFi 暂时不能上网而被系统改走移动数据

本项目的认证流程移植自电脑版 [HSTC-campus-login](https://github.com/Agoin-314260/HSTC-campus-login)，详见文末「致谢」。

## 使用

1. 安装 `HSTC-CampusLogin-v<版本号>.apk`（Android 8.0 及以上）
2. 连接校园网 WiFi（手机提示“无法上网 / 需要登录”时不用管，也不用关移动数据）
3. 打开 App，输入账号（12 位）和密码，点「登录」
4. 以后每次连上校园网，打开 App 即自动登录（如需重试，点「登录」）；点「清除已保存的账号」可取消自动登录

界面顶部会实时显示 WiFi 状态：未连接 / 已连接未认证 / 已可上网；下方「登录日志」显示每一步的执行情况，便于排查问题。

## 核心问题：未认证的 WiFi 会被系统绕开

连上校园网 WiFi 但尚未认证时，Android 的联网验证会失败，系统于是**继续把移动数据作为默认网络**（小米、华为等还有「WLAN 助理 / 智能切换」加剧这一点）。此时 App 发出的普通请求会从流量出去，校内认证服务器（`192.168.2.34`、`rz.hstc.edu.cn` 等）根本收不到。

本 App 在 `WifiNetwork.kt` 中这样处理：

| 措施 | 作用 |
|---|---|
| `ConnectivityManager.requestNetwork(TRANSPORT_WIFI)` | 显式申请 WiFi 网络对象，**不要求已验证（VALIDATED）**，未认证的 WiFi 也能拿到；申请期间系统也不会闲置/断开这张 WiFi |
| OkHttp `socketFactory(network.socketFactory)` | 每个 TCP 连接都绑定到 WiFi 网卡，不管默认网络是谁 |
| OkHttp `dns { network.getAllByName(it) }` | 域名用 WiFi 的 DNS 解析（校内 DNS），不走流量的 DNS |
| `bindProcessToNetwork(network)` | 登录期间把整个进程临时绑定到 WiFi 兜底，结束后恢复 |
| `Proxy.NO_PROXY` | 不经过系统代理 |
| `reportNetworkConnectivity(network, true)` | 认证成功后让系统立即重新验证，WiFi 更快变成「可上网」并接管默认网络 |

IP、网关直接从这张 WiFi 的 `LinkProperties` 读取，保证填进认证参数的是 WiFi 网卡的地址，而不是流量网卡的。

## 认证流程

移植自电脑版 [HSTC-campus-login](https://github.com/Agoin-314260/HSTC-campus-login) 的 `login_core.py`（见 `CampusLoginClient.kt`）：

1. 通过 WiFi 访问 `http://rz.hstc.edu.cn/`，3 秒内返回 200 视为在校园网
2. 把 `1|0|IP||MAC||网关|接入点|` Base64 后作为门户 `state` 参数，门户地址整体作为 CAS 的 `service` 参数
3. GET CAS 登录页，提取隐藏域 `execution`
4. GET `/cas/jwt/publicKey`，密码用 RSA（PKCS#1 v1.5）加密、Base64 后加 `__RSA__` 前缀
5. POST 登录表单，跟随 302 跳回门户（HTTPS → HTTP）
6. 按返回页标题判定：`认证成功页` → 成功；`信息页` → 已在线；HTTP 401 → 账号或密码错误

## 常见问题

- **提示“域名解析失败”**：系统设置里开启了「私人 DNS」（如 dns.google），未认证时连不上外部 DNS。改为「自动」或「关闭」后重试。
- **开着 VPN / 代理 App**：部分 VPN 不允许应用绑定到指定网络，请先关闭再登录。
- **MAC 地址**：Android 10+ 普通应用读不到网卡 MAC，此时认证参数里的 MAC 填 `000000000000`。电脑版同样可能上报非 WiFi 网卡的 MAC 且能正常认证，说明门户不校验此字段；若以后学校开始校验，需要另行适配。

## 从源码构建

需要 JDK 17+ 和 Android SDK（compileSdk 35），用 Android Studio 打开，或命令行：

```bash
./gradlew testReleaseUnitTest   # 单元测试（用 MockWebServer 模拟 CAS 完整流程）
./gradlew assembleRelease       # 产物：app/build/outputs/apk/release/app-release.apk
```

最低支持 Android 8.0（API 26）。

### 发布签名

release 包的签名从项目根目录的 `keystore.properties` 读取（该文件和 keystore 均不入库）：

```properties
storeFile=keystore/campuslogin-release.jks
storePassword=******
keyAlias=campuslogin
keyPassword=******
```

生成证书：

```bash
keytool -genkeypair -v -keystore keystore/campuslogin-release.jks -storetype PKCS12 -keyalg RSA -keysize 2048 -validity 36500 -alias campuslogin
```

请妥善备份 keystore 与密码：签名一旦更换，已安装的旧版本无法直接覆盖升级。

## 目录说明

| 文件 | 说明 |
|---|---|
| `MainActivity.kt` | 登录界面、WiFi 状态显示、登录日志 |
| `WifiNetwork.kt` | 申请 WiFi 网络并创建绑定到 WiFi 的 HTTP 客户端 |
| `CampusLoginClient.kt` | CAS 认证流程 |
| `RsaUtil.kt` | CAS 公钥解析与密码加密 |
| `CredentialStore.kt` | Keystore AES-GCM 加密保存账号密码 |
| `MemoryCookieJar.kt` | 登录期间的内存 Cookie（保持 CAS 会话） |

## 致谢

- 校园网认证流程（门户参数构造、CAS 登录、RSA 密码加密、结果判定）参考并移植自 [Agoin-314260/HSTC-campus-login](https://github.com/Agoin-314260/HSTC-campus-login)（MIT License），感谢原作者的分析与开源。
- 本项目是独立开发的安卓移植版，与原项目作者无隶属关系，也不是其官方手机版；安卓版的问题请勿反馈到原项目。
- 本项目与学校官方无关，仅供学习交流使用。

## 许可

[MIT License](LICENSE)。本项目包含源自 [HSTC-campus-login](https://github.com/Agoin-314260/HSTC-campus-login) 的实现，依据其 MIT 许可保留了原作者的版权声明，见 [LICENSE](LICENSE)。
