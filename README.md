# LanChat-Android

局域网多人聊天 App（Android）—— 纯 Java 手搓 socket，无第三方网络/加密库。支持文本/文件传输（AES 加密、分片断点续传）、局域网扫描发现服务端、心跳保活、消息一键复制。

## 功能

- **多人聊天**：服务端广播模式，一个服务端可连接多个客户端
- **消息加密**：文本/系统消息与文件分片均采用 AES-256-CBC（随机 IV），密码由双方约定，留空用内置默认密码
- **文本消息**：实时收发文字，每条消息右侧「复制」按钮一键复制
- **文件传输**：发送本地文件，自动保存到 `/storage/emulated/0/局域网聊天/`
- **断点续传**：文件分片传输（256KB/片），接收进度持久化，中断后重发同一文件自动跳过已收分片
- **心跳保活**：30 秒 PING/PONG + 90 秒超时检测 + WakeLock，锁屏不断连
- **局域网扫描**：点击「扫描」自动发现局域网内开启的服务端，点击即自动连接
- **前台服务保活**：后台前台服务保持连接不断开
- **IP 置顶**：本机 IP 与端口显示在界面顶部

## 协议

| 类型 | 值 | 说明 |
|------|-----|------|
| 文本 | `0x01` | 文本消息（内容 AES 加密） |
| 系统 | `0x03` | 系统通知（内容 AES 加密） |
| 文件开始 | `0x06` | 文件名、大小、分片大小、总分片数 |
| 文件分片 | `0x07` | 分片索引 + AES 加密的分片数据 |
| 续传请求 | `0x08` | 接收方回复已收分片数，发送方跳过续传 |
| 传输完成 | `0x09` | 接收方确认全部收完 |
| 心跳 | `0x0A`/`0x0B` | PING/PONG 保活，90 秒无响应断开 |

> 连接注册（昵称）走明文 `writeUTF`/`readUTF`；TEXT/SYSTEM/FILE 全部走加密格式。改协议时务必逐字段核对写/读对称性。

## 下载

- **Release APK**：[Releases 页](https://github.com/yanzaiyun43/LanChat-Android/releases)，每个 `v*` 标签自动构建并发布带签名的 APK
- **CI 产物**：push 到 `main` 后可在 Actions 页面下载 `app-debug` 产物

## 构建

```bash
git clone https://github.com/yanzaiyun43/LanChat-Android.git
cd LanChat-Android
./gradlew assembleDebug
```

本地开发无需任何签名配置——debug 构建自动用默认 debug keystore 签名。

## 签名

CI 构建的 APK 用固定的发布签名，保证每次构建签名一致、可覆盖升级（无需卸载旧版）。

- 签名 keystore 存于 GitHub Actions 加密 secret（`LANCHAT_KEYSTORE` 等），**不入仓库**，密钥不公开
- `app/build.gradle` 通过环境变量读取签名（CI 注入 secret），本地无该环境变量时回退默认 debug keystore
- `.github/workflows/build.yml` 在构建前解码 secret 到临时文件并注入环境变量

> v2.0 起启用全新签名密钥，与历史版本（v1.x）签名不兼容，从 v1.x 升级到 v2.0 需卸载重装一次；v2.0 之后版本签名连续，可平滑覆盖升级。

## 使用

1. 一方点击「开启服务端」
2. 其他人点击「扫描」，选择发现的服务端自动连接
3. 输入昵称与加密密码（双方需一致，留空用默认密码），发送文字或点击「选择并发送文件」
4. 收到消息点「复制」即可复制内容（系统提示不显示复制按钮）
5. 文件传输中断后，重新发送同一文件即可从断点继续

## 权限

- `INTERNET` / `ACCESS_NETWORK_STATE` — 局域网通信
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` — 前台服务保活
- `WAKE_LOCK` — 锁屏后保持 CPU 运行，防止断连
- `MANAGE_EXTERNAL_STORAGE` — 保存文件到公开目录

> Android 11+ 需在「设置 → 应用 → 权限 → 所有文件访问」中手动开启 `com.lans.chat`，否则文件保存到应用私有目录。

## 技术栈

- Java + Android SDK（minSdk 24，targetSdk 34）
- Gradle 8.5 + AGP 8.2.0
- androidx.appcompat:appcompat 1.6.1 / androidx.activity:activity 1.8.2 / material 1.11.0

## CI

- push 到 `main`：构建 debug APK（Actions 页面可下载产物）
- 创建 `v*` 标签：自动构建并发布 Release（附签名 APK）
- 版本号从 git 推导：`versionName` = `git describe --tags`，`versionCode` = 提交数 + 偏移；发版只需 `git tag v2.1 && git push origin v2.1`，无需改任何文件
- checkout 用 `fetch-depth: 0` 以保证能算出 tag 与提交数
- 端口 9876，包名 `com.lans.chat`

## 版本

| 版本 | 说明 |
|------|------|
| 2.1 | 版本号改由 git tag 推导，移除 version.properties 与 CI 自动 bump |
| 2.0 | 全新固定签名密钥（Actions secret，不入库）；CI 签名机制固化；README 重写 |

> v1.x 为历史版本（version.properties + 自动 bump 旧机制），签名不兼容，不再维护。
