# 豆包语音输入法

这是一个 Android 输入法 App，不再走系统悬浮窗和无障碍，而是按真正的 `InputMethodService` 实现：

- 点进任意输入框时，系统会像普通键盘一样自动弹出这个输入法。
- 输入法面板里点 `开始说话`，会走豆包流式语音识别，再可选调用豆包模型纠错，最后直接写入当前输入框。
- 点 `修正全文`，会读取当前输入框文本并尝试整体替换成模型修正后的版本。

## 当前实现

- 输入法层：
  - 使用 `InputMethodService` 承载一个小型语音面板，不需要悬浮窗权限。
  - 通过 `InputConnection` 直接向当前输入框提交文本，不依赖 `AccessibilityService`。
- 豆包语音：
  - 只保留豆包流式语音识别 2.0 这一条接入路径。
  - 地址固定为 `wss://openspeech.bytedance.com`，URI 固定为 `/api/v3/sauc/bigmodel`，请求参数已内置。
  - 使用官方 Android `SpeechEngine` SDK，并用反射封装，降低 SDK 小版本差异带来的编译风险。
- 火山方舟纠错：
  - 通过 OpenAI 兼容的 `chat/completions` 接口调用模型。
  - 语音识别结束后的纠错和“修正全文”都走这一条链路。
- 主页面：
  - 只保留豆包语音和豆包模型最少配置项。
  - 带两条独立测试：语音识别测试、LLM 测试。
  - 带一个示例输入框，方便手动验证 IME 是否会自动弹出。

## 配置项

主页面只保留 5 个字段：

1. 豆包语音 `App ID`
2. 豆包语音 `Access Token`
3. 豆包语音 `Resource ID / 实例 ID`
4. 火山方舟 `API Key`
5. 豆包模型 `Endpoint ID / Model ID`

这些参数已经固定内置，不需要再填：

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
4. 授予麦克风权限。
5. 点 `保存配置`。
6. 进入系统输入法设置，启用“豆包语音输入法”。
7. 切换当前输入法到“豆包语音输入法”。
8. 点任意输入框，输入法面板会自动出现。

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
  - workflow 会自动创建 GitHub Release，并把 `sds-mobile-debug.apk` 作为 release asset 上传

## 参考的官方文档

- Android `InputMethodService`：<https://developer.android.com/reference/android/inputmethodservice/InputMethodService>
- 豆包语音 Android 集成指南：<https://www.volcengine.com/docs/6561/113641>
- 豆包语音 Android 调用流程：<https://www.volcengine.com/docs/6561/113642>
- 大模型流式识别 SDK：<https://www.volcengine.com/docs/6561/1395846>
- 火山方舟 Chat API：<https://www.volcengine.com/docs/82379/1494384>
