# 语音输入法

这是一个 Android 输入法 App，按真正的 `InputMethodService` 实现：

- 点进任意输入框时，系统会像普通键盘一样自动弹出这个输入法。
- 输入法面板里会出现一个小球，轻点开始说话，再轻点结束录音。
- 长按这个小球，会读取当前输入框文本并尝试整体替换成模型修正后的版本。

## 当前实现

- 输入法层：
  - 使用 `InputMethodService` 承载一个小型语音球，不需要悬浮窗权限。
  - 通过 `InputConnection` 直接向当前输入框提交文本，不依赖 `AccessibilityService`。
  - 如果打开“加载屏幕识别（Beta）”，会额外启用一个可选的 `AccessibilityService`，只把当前屏幕文本当作模型上下文参考。
- 豆包语音：
  - 只保留豆包流式语音识别 2.0 这一条接入路径。
  - 地址固定为 `wss://openspeech.bytedance.com`，URI 固定为 `/api/v3/sauc/bigmodel_async`，请求参数已内置。
  - 使用官方 Android `SpeechEngine` SDK，并用反射封装，降低 SDK 小版本差异带来的编译风险。
- 火山方舟纠错：
  - 通过 OpenAI 兼容的 `chat/completions` 接口调用模型。
  - 轻点输入时，可以按“识别后自动文本优化”开关决定是否在识别后再走一轮模型优化。
  - 长按小球修正全文始终走这一条链路。
- 主页面：
  - 保留豆包语音、豆包模型和文本优化相关配置项。
  - 带两条独立测试：SDK 语音识别测试、LLM 测试。
  - 带一个示例输入框，方便手动验证 IME 是否会自动弹出。

## 配置项

主页面包含这几类配置：

1. 豆包语音 `Speech Access Token`
2. 文本优化开关和整理选项
3. 火山方舟 `API Key`
4. 豆包模型 `Model ID`

这些参数已经固定内置，不需要再填：

- 语音 App ID：`2586725503`
- 语音 Resource ID：`volc.seedasr.sauc.duration`
- 语音地址：`wss://openspeech.bytedance.com`
- 语音 URI：`/api/v3/sauc/bigmodel_async`
- 方舟 Base URL：`https://ark.cn-beijing.volces.com/api/v3`
- 方舟默认 Model ID：`doubao-seed-2-0-pro-260215`
- 纠错 System Prompt

## 手机上怎么试

1. 安装 release 页面里的 `sds-mobile-release.apk`。
2. 打开 App，填上面 3 个字段。
3. 先在首页测试：
   - `开始 SDK 语音测试`：测试豆包语音 SDK，结果只显示在当前页面。
   - `测试 LLM`：测试豆包模型纠错，结果也只显示在当前页面。
4. 授予麦克风权限。
5. 点 `保存配置`。
6. 进入系统输入法设置，启用“语音输入法”。
7. 切换当前输入法到“语音输入法”。
8. 点任意输入框，输入法面板会自动出现。
9. 轻点小球开始说话，再轻点结束录音；长按小球会修正当前输入框全文。
10. 如果你打开了“加载屏幕识别（Beta）”，再去系统无障碍里启用“语音输入法屏幕识别”。

## GitHub Actions 打包

仓库里已经带了一个 GitHub Actions 工作流：

- 文件：`.github/workflows/android-debug.yml`
- 触发方式：
  - push 到 `main` 或 `master`
  - 在 GitHub Actions 页面手动点击 `Run workflow`
- 产物：
  - `sds-mobile-release-apk`
  - 内含固定签名后的 `sds-mobile-release.apk`
- 公开下载：
  - workflow 会自动创建 GitHub Release，并把 `sds-mobile-release.apk` 作为 release asset 上传

## 参考的官方文档

- Android `InputMethodService`：<https://developer.android.com/reference/android/inputmethodservice/InputMethodService>
- 豆包语音 Android 集成指南：<https://www.volcengine.com/docs/6561/113641>
- 豆包语音 Android 调用流程：<https://www.volcengine.com/docs/6561/113642>
- 大模型流式识别 SDK：<https://www.volcengine.com/docs/6561/1395846>
- 火山方舟 Chat API：<https://www.volcengine.com/docs/82379/1494384>
