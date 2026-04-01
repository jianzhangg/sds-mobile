# 豆包语音悬浮输入

这是一个从零搭的 Android 工程，目标是做一个“悬浮球式语音输入”：

- 点按悬浮球：启动/结束豆包流式语音识别，然后用火山方舟模型纠正文本，再写回当前输入框。
- 长按悬浮球：读取当前输入框已有内容，调用火山方舟模型修正后整体替换。
- 配置页：填写豆包语音与火山方舟两套凭据，并引导开启悬浮窗、麦克风和无障碍。

## 当前实现

- 豆包语音：
  - 只保留豆包流式语音识别 2.0 这一条接入路径。
  - 地址固定为 `wss://openspeech.bytedance.com`，URI 固定为 `/api/v3/sauc/bigmodel`，请求参数也已内置。
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
  - 当前仓库已经通过 GitHub Actions 自动打 debug APK，不依赖你本地先装 Android Studio。

## 配置项

主页面现在只保留 5 个字段：

1. 豆包语音 `App ID`
2. 豆包语音 `Access Token`
3. 豆包语音 `Resource ID / 实例 ID`
4. 火山方舟 `API Key`
5. 豆包模型 `Endpoint ID / Model ID`

其中这些参数已经固定内置，不需要再填：

- 语音地址：`wss://openspeech.bytedance.com`
- 语音 URI：`/api/v3/sauc/bigmodel`
- 方舟 Base URL：`https://ark.cn-beijing.volces.com/api/v3`
- 纠错 System Prompt

## 手机上怎么试

1. 安装 release 页面里的 `sds-mobile-debug.apk`。
2. 打开 App，填上面 5 个字段。
3. 先在首页测试：
   - `开始语音识别测试`：测试豆包语音 SDK，结果只显示在当前页面。
   - `测试 LLM`：测试豆包模型纠错，结果也只显示在当前页面。
4. 测通后，再授予：
   - 麦克风权限
   - 悬浮窗权限
   - 无障碍服务
   - 通知权限（Android 13+）
5. 点击 `启动悬浮球`。

## GitHub Actions 打包

仓库里已经带了一个 GitHub Actions 工作流：

- 文件：`.github/workflows/android-debug.yml`
- 触发方式：
  - push 到 `main` 或 `master`
  - 在 GitHub Actions 页面手动点击 `Run workflow`
- 产物：
  - `sds-mobile-debug-apk`
  - 内含 `app/build/outputs/apk/debug/` 下生成的 debug APK
- 公开下载：
  - workflow 也会自动创建 GitHub Release，并把 `sds-mobile-debug.apk` 作为 release asset 上传

## 参考的官方文档

- 豆包语音 Android 集成指南：<https://www.volcengine.com/docs/6561/113641>
- 豆包语音 Android 调用流程：<https://www.volcengine.com/docs/6561/113642>
- 大模型流式识别 SDK：<https://www.volcengine.com/docs/6561/1395846>
- 火山方舟 Chat API：<https://www.volcengine.com/docs/82379/1494384>
