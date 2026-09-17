# Video Source Detection / 片源检测

Android 视频片源素质检测器。项目的第一优先级不是本地文件，而是 **在线视频、视频网站和其他视频 App 的实时播放场景**。

## v0.1 已实现

- MediaProjection 实时采样其他 App 的最终渲染画面。
- 悬浮 HUD：当前前台 App、屏幕输出分辨率、有效细节分、纹理量、升采样嫌疑、置信度。
- 2× 插值周期启发式：用于发现常见的 1080P -> 2160P、低分辨率放大后重新编码的空间周期痕迹。
- 多区域、多帧滚动采样，降低单帧/纯色镜头误判。
- Root 可选增强：尝试从 `dumpsys media.codec` / `dumpsys media.metrics` 读取活动视频编码尺寸、MIME、帧率。
- DRM / `FLAG_SECURE` / 黑场识别：无法采样时明确提示，不把黑屏误判成低清。
- HLS (`.m3u8`) / DASH (`.mpd`) 清单分析，并支持从其他 App “分享”链接到本 App。
- GitHub Actions 自动构建 debug APK。

## 这几个概念必须分开

- **编码/信号分辨率**：例如码流确实写着 3840×2160。
- **屏幕输出分辨率**：手机实际能显示多少像素。
- **有效细节**：最终画面里有多少独立空间高频信息。

一个 1920×1080 视频先放大到 3840×2160 再编码，播放器和 MediaCodec 都会把它报告成“4K”。所以本项目不会用 `width × height` 直接宣称“原生 4K”。

## 当前边界

普通第三方 Android App 无权直接读取另一个 App 的私有 `MediaCodec` 对象，也无法绕过 DRM 安全 Surface。MediaProjection 看到的是最终合成画面，因此当手机屏幕本身低于 2160P 时，不能仅凭屏幕采样严格证明一个 4K 流的母版就是原生 4K。v0.1 会在这种场景直接提示“无法仅凭屏幕帧确认原生 4K”。

Root 探针也是 best-effort：不同 ROM 的 `dumpsys` 输出格式不同，解析不到时不会伪造数据。

## 下一阶段

1. Shizuku collector（不要求完整 Root）读取更多系统媒体诊断信息。
2. 可选 LSPosed companion 模块，在目标播放器进程内采集 `MediaCodec.configure()` / `MediaFormat`，提高活动码流识别准确率。
3. 1.5× / 4:3 等更多缩放周期检测、小波/频谱特征、多尺度 round-trip loss。
4. HDR、10-bit、色域、帧率、掉帧和硬解/软解状态。
5. 针对常见视频网站的 manifest adapter（在合法可访问的登录态/公开接口范围内）。
6. 标准测试片校准，给不同设备建立阈值 profile，减少内容类型导致的假阳性。

## 构建

项目使用 Android Gradle Plugin 9.4.0、Gradle 9.6.0、JDK 17，`compileSdk/targetSdk = 36`。官方 Android CI 工作流会在每次 push 后构建：

```bash
gradle :app:assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`

## 隐私

v0.1 的屏幕帧分析在设备本地完成，不上传截图。链接分析只会向用户提供的 URL 发起读取请求。
