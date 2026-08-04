# TCL 适配版

目标设备：照片中的 `V8-MS81802-LF1V112`，Android `4.2.2 / API 17`，约 `695 MB RAM`。

本目录增加轻量 Android 客户端：

- `minSdk 17`，横屏，遥控器焦点导航。
- 纯 Java 平台控件，无 AndroidX/Media3，降低旧电视内存压力。
- 内置动漫目录：动漫巴士、海外看、暴风、索尼、快帆、量子、非凡。
- 启动后每 6 小时检查 GitHub 目录更新；失败时继续使用本地缓存。
- 规则通过 U 盘文件或手机局域网上传导入，电视界面不再输入长 JSON/URL。
- 支持公开 HTML/CMS 规则的搜索、剧集提取、相对 URL、`m3u8/mp4` 播放地址。
- API 17 上为 HTTPS 尝试启用 TLS 1.2。

## 规则格式

客户端执行数据规则，不执行 JavaScript、Spider JAR 或外部解析器。最小示例：

```json
{
  "id": "example",
  "name": "示例站点",
  "baseUrl": "https://example.invalid",
  "search": {
    "url": "https://example.invalid/search/{keyword}",
    "itemPattern": "<a[^>]+href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>",
    "urlGroup": 1,
    "titleGroup": 2
  },
  "episodes": {
    "itemPattern": "<a[^>]+href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>",
    "urlGroup": 1,
    "titleGroup": 2
  },
  "video": {
    "urlPattern": "(https?://[^\\\"']+\\.(?:m3u8|mp4)(?:\\?[^\\\"']*)?)",
    "urlGroup": 1
  },
  "charset": "UTF-8"
}
```

`{keyword}` 和 `{q}` 会替换为 URL 编码后的搜索词。`baseUrl` 用于补全相对链接。`filter` 为可选的标题/链接包含过滤词。

## 自动更新与手机上传

内置目录路径：`rules/anime.json`。默认远程地址：
`https://raw.githubusercontent.com/Kwusv55/tvbox-tcl/master/rules/anime.json`。

电视选择“手机上传”后会显示局域网地址和一次性 PIN。手机打开该地址，选择 JSON 文件上传；校验成功后电视立即替换目录。电视与手机需在同一 Wi-Fi，上传服务在退出应用时关闭。

## qist/tvbox 说明

上游 `qist/tvbox` 是配置集合，不是 Android APK。`jsm.json` 等配置依赖专用 TVBox 内核、Spider JAR、JavaScript 和 `csp_*` 实现；本适配客户端不会执行这些代码，因此直接导入含 `sites` 的上游配置会提示格式不兼容。需要完整 qist 配置时，使用原生 TVBox 内核；需要本客户端时，转换为上面的轻量规则格式。

## 构建与安装

需要 JDK 17、Android SDK 33、Gradle 7.6.4：

```text
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可使用 GitHub Actions 的 `Android APK` workflow，下载 `tvbox-tcl-debug` artifact，再通过 U 盘或 ADB 安装。APK 为通用 Java APK，不依赖 App Bundle。
