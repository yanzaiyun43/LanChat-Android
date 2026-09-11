# LanChat-Android

局域网多人聊天 APP (Android) — 支持文本/文件传输，局域网扫描发现服务端，点击自动连接。

## 功能

- **多人聊天**：服务端广播模式，一个服务端可连接多个客户端
- **文本消息**：实时收发文字消息
- **文件传输**：支持发送本地文件，自动保存到 `/storage/emulated/0/局域网聊天/`
- **局域网扫描**：点击「扫描」按钮自动发现局域网内开启的服务端，点击即可自动连接
- **昵称设置**：自定义聊天昵称
- **前台服务保活**：后台前台服务保持连接不断开
- **IP 置顶**：本机 IP 和端口显示在界面顶部

## 协议

| 类型 | 值 | 说明 |
|------|-----|------|
| 文本 | `0x01` | 发送文本消息 |
| 文件 | `0x02` | 发送文件 |
| 系统 | `0x03` | 系统通知（用户加入/离开） |
| 昵称 | `0x04` | 昵称注册 |

## 下载

GitHub Actions 自动构建，每次 push 到 `main` 分支或创建 `v*` 标签时触发：

- **CI 构建**：push 到 `main` 后，在 Actions 页面下载 `app-debug` 产物
- **正式发布**：创建 `v*` 标签后，自动发布 Release 并附带 APK

```bash
# 创建标签发布
git tag v1.5
git push origin v1.5
```

## 构建

```bash
# 克隆项目
git clone https://github.com/yanzaiyun43/LanChat-Android.git
cd LanChat-Android

# 使用 Gradle 构建
./gradlew assembleDebug
```

## 使用

1. 一方点击「开启服务端」
2. 其他人点击「扫描」，选择发现的服务端即可自动连接
3. 输入昵称，发送文字或点击「选择并发送文件」

## 权限

- `INTERNET` / `ACCESS_NETWORK_STATE` — 局域网通信
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` — 前台服务保活
- `MANAGE_EXTERNAL_STORAGE` — 保存文件到公开目录 `/storage/emulated/0/局域网聊天/`

> Android 11+ 需在「设置 → 应用 → 权限 → 所有文件访问」中手动开启 `com.lans.chat`，否则文件保存到应用私有目录。

## 技术栈

- Java + Android SDK (minSdk 24, targetSdk 34)
- Gradle 8.5 + AGP 8.2.0
- androidx.appcompat:appcompat 1.6.1
- androidx.activity:activity 1.8.2

## 版本历史

| 版本 | 说明 |
|------|------|
| 1.0 | 基础 1 对 1 聊天 |
| 1.1 | 多人聊天、昵称、服务端/客户端切换、IP 置顶、图标替换 |
| 1.2 | 修复文件 race condition、文件保存到公开目录、前台服务保活 |
| 1.3 | 修复 Android 14 FGS `dataSync` 崩溃 |
| 1.4 | 局域网扫描发现服务端、自动跳转权限页面 |
| 1.5 | 扫描发现的服务端点击自动连接 |
