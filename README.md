# 豆包语音悬浮输入

这是一个从零搭的 Android 工程，目标是做一个“悬浮球式语音输入”：

- 点按悬浮球：启动/结束豆包流式语音识别，然后用火山方舟模型纠正文本，再写回当前输入框。
- 长按悬浮球：读取当前输入框已有内容，调用火山方舟模型修正后整体替换。
- 配置页：填写豆包语音与火山方舟两套凭据，并引导开启悬浮窗、麦克风和无障碍。

## 当前实现

- 豆包语音：
  - 默认支持大模型流式识别配置，地址默认为 `wss://openspeech.bytedance.com`，URI 默认为 `/api/v3/sauc/bigmodel`。
  - 也保留了“标准 ASR”模式，可切回 `/api/v2/asr + cluster`。
  - 使用官方 Android `SpeechEngine` SDK，并用反射封装，降低 SDK 小版本差异带来的编译风险。
- 火山方舟纠错：
  - 通过 OpenAI 兼容的 `chat/completions` 接口调用模型。
  - 识别后纠错与“修正当前输入框文本”都走这一条链路。
- 输入框控制：
  - 通过 `AccessibilityService` 获取当前焦点输入框并执行 `ACTION_SET_TEXT`。
  - 若点按输入时没有可编辑输入框，会把结果复制到剪贴板。

## 你需要注意

- 这是“悬浮球输入工具”，不是系统 `InputMethodService` 键盘。
  - 你提的交互是悬浮球点击/长按，落地上更适合 `SYSTEM_ALERT_WINDOW + AccessibilityService`。
- 豆包语音 SDK 官方文档标注 Android 仅支持 `armeabi-v7a / arm64-v8a`。
  - 因此更适合真机，x86 模拟器大概率不行。
- 当前环境没有 Java / Gradle / Android SDK，所以我没法在这里实际编译 APK。
  - 工程文件已经落齐，但 `gradle-wrapper.jar` 没法在本地自动生成，需要你在 Android Studio 打开后同步一次。

## 打开工程后建议

1. 用 Android Studio 直接打开本目录。
2. 让 IDE 自动补齐 Gradle Wrapper。
3. 在真机上安装并授予：
   - 麦克风权限
   - 悬浮窗权限
   - 无障碍服务
   - 通知权限（Android 13+）
4. 在主页面填入：
   - 豆包语音 `App ID/App Key`
   - `Token`
   - 大模型模式下的 `Resource ID`
   - 火山方舟 `API Key`
   - `Model / Endpoint ID`

## GitHub Actions 打包

仓库里已经带了一个 GitHub Actions 工作流：

- 文件：`.github/workflows/android-debug.yml`
- 触发方式：
  - push 到 `main` 或 `master`
  - 在 GitHub Actions 页面手动点击 `Run workflow`
- 产物：
  - `sds-mobile-debug-apk`
  - 内含 `app/build/outputs/apk/debug/` 下生成的 debug APK

## 参考的官方文档

- 豆包语音 Android 集成指南：<https://www.volcengine.com/docs/6561/113641>
- 豆包语音 Android 调用流程：<https://www.volcengine.com/docs/6561/113642>
- 大模型流式识别 SDK：<https://www.volcengine.com/docs/6561/1395846>
- 火山方舟 Chat API：<https://www.volcengine.com/docs/82379/1494384>
