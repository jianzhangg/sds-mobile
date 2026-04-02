package com.sdsmobile.voiceime.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sdsmobile.voiceime.VoiceImeApplication
import com.sdsmobile.voiceime.model.AppSettings
import com.sdsmobile.voiceime.service.ScreenContextAccessibilityService
import com.sdsmobile.voiceime.service.VoiceInputMethodService
import com.sdsmobile.voiceime.ui.theme.SdsVoiceImeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                val container = (application as VoiceImeApplication).appContainer
                MainViewModel(
                    appContext = applicationContext,
                    repository = container.settingsRepository,
                    arkTextCorrector = container.arkTextCorrector,
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SdsVoiceImeTheme {
                Surface {
                    MainScreen(viewModel)
                }
            }
        }
    }
}

@Composable
private fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showTestingDialog by rememberSaveable { mutableStateOf(false) }
    var sampleText by rememberSaveable { mutableStateOf("点击这里测试输入法自动弹出") }
    var audioGranted by rememberSystemSettingState {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }
    var imeEnabled by rememberSystemSettingState { isImeEnabled(context) }
    var imeSelected by rememberSystemSettingState { isImeSelected(context) }
    var screenContextEnabled by rememberSystemSettingState { isScreenContextServiceEnabled(context) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        audioGranted = granted
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFFFF7ED), Color(0xFFFFEDD5), Color(0xFFF8FAFC)),
                ),
            ),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeroCard(
                    imeEnabled = imeEnabled,
                    imeSelected = imeSelected,
                )

                SetupCard(
                    audioGranted = audioGranted,
                    imeEnabled = imeEnabled,
                    imeSelected = imeSelected,
                    screenContextEnabled = screenContextEnabled,
                    onAudioClick = { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onOpenImeSettings = { openInputMethodSettings(context) },
                    onShowImePicker = { showInputMethodPicker(context) },
                    onOpenAccessibilitySettings = { openAccessibilitySettings(context) },
                )

                SettingsCard(
                    settings = uiState.draft,
                    screenContextServiceEnabled = screenContextEnabled,
                    onUpdate = viewModel::updateDraft,
                    onOpenAccessibilitySettings = { openAccessibilitySettings(context) },
                )

                SampleEditorCard(
                    value = sampleText,
                    onValueChange = { sampleText = it },
                )

                TestingEntryCard(
                    onOpenTestingDialog = { showTestingDialog = true },
                )

                ActionCard(
                    onSave = {
                        scope.launch {
                            viewModel.persistDraft()
                        }
                    },
                    onSaveAndPick = {
                        scope.launch {
                            viewModel.persistDraft()
                            showInputMethodPicker(context)
                        }
                    },
                )
            }
        }

        uiState.speechTest.errorDialog?.let { dialogText ->
            AlertDialog(
                onDismissRequest = viewModel::dismissSpeechErrorDialog,
                confirmButton = {
                    TextButton(onClick = viewModel::dismissSpeechErrorDialog) {
                        Text("知道了")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(dialogText))
                        },
                    ) {
                        Text("复制内容")
                    }
                },
                title = { Text("语音测试失败") },
                text = {
                    Text(
                        text = dialogText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
        }

        if (showTestingDialog) {
            AlertDialog(
                onDismissRequest = { showTestingDialog = false },
                confirmButton = {
                    TextButton(onClick = { showTestingDialog = false }) {
                        Text("关闭")
                    }
                },
                title = { Text("连通性测试") },
                text = {
                    TestingDialogContent(
                        speechTest = uiState.speechTest,
                        llmTest = uiState.llmTest,
                        onRunSpeechTest = viewModel::runSpeechTest,
                        onCopySpeechReport = {
                            clipboardManager.setText(AnnotatedString(buildSpeechTestReport(uiState.speechTest)))
                        },
                        onLlmInputChange = viewModel::updateLlmTestInput,
                        onRunLlmTest = viewModel::runLlmTest,
                    )
                },
            )
        }
    }
}

@Composable
private fun HeroCard(
    imeEnabled: Boolean,
    imeSelected: Boolean,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "语音输入法",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "轻点悬浮球开始语音输入，再轻点结束；长按悬浮球会优化当前输入框全文。点击输入默认可直接输出原始识别文本，也可以按开关走一轮大模型文本优化。",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFD1D5DB),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    text = if (imeEnabled) "输入法已启用" else "输入法未启用",
                    active = imeEnabled,
                )
                StatusChip(
                    text = if (imeSelected) "当前已选中" else "当前未选中",
                    active = imeSelected,
                )
            }
        }
    }
}

@Composable
private fun SetupCard(
    audioGranted: Boolean,
    imeEnabled: Boolean,
    imeSelected: Boolean,
    screenContextEnabled: Boolean,
    onAudioClick: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    SectionCard(
        title = "启用步骤",
        subtitle = "基础使用只需要麦克风和输入法权限。屏幕识别是可选增强，只在你打开“加载屏幕识别（Beta）”时才需要无障碍。",
        icon = Icons.Outlined.Keyboard,
    ) {
        PermissionRow("麦克风权限", audioGranted, onAudioClick)
        PermissionRow("已在系统里启用输入法", imeEnabled, onOpenImeSettings)
        PermissionRow("当前输入法已切换到本 App", imeSelected, onShowImePicker)
        PermissionRow("屏幕识别（可选）", screenContextEnabled, onOpenAccessibilitySettings)
    }
}

@Composable
private fun SettingsCard(
    settings: AppSettings,
    screenContextServiceEnabled: Boolean,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionCard(
            title = "豆包语音",
            subtitle = "按豆包流式语音识别 2.0 接入。App ID 和资源 ID 已固定，只需要填写 Access Token。",
            icon = Icons.Outlined.GraphicEq,
        ) {
            SecretField(
                label = "Speech Access Token",
                value = settings.speechToken,
                onValueChange = { onUpdate { current -> current.copy(speechToken = it) } },
                supportingText = "固定使用 App ID 2586725503 和小时版资源 volc.seedasr.sauc.duration，只需要填写控制台里的 Access Token，不要加 Bearer 前缀。",
            )
            ConsoleGuideRow(
                label = "去豆包语音控制台查看 Access Token",
                onClick = { openWebPage(context, SPEECH_CONSOLE_URL) },
            )
        }

        SectionCard(
            title = "文本优化",
            subtitle = "“识别后自动文本优化”只控制轻点说话后的后处理；长按悬浮球优化当前输入框全文的功能保持不变。",
            icon = Icons.Outlined.Tune,
        ) {
            SwitchField(
                label = "识别后自动文本优化",
                checked = settings.textOptimizationEnabled,
                supportingText = "打开后，点击悬浮球录入的文本会先经过一轮大模型优化；关闭时直接输出豆包语音识别原文。",
                onCheckedChange = {
                    onUpdate { current -> current.copy(textOptimizationEnabled = it) }
                },
            )
            SwitchField(
                label = "个性化偏好",
                checked = settings.personalizationEnabled,
                supportingText = "告诉语音输入法你的表达习惯和偏好。关闭时不会把偏好文本发给模型。",
                onCheckedChange = {
                    onUpdate { current -> current.copy(personalizationEnabled = it) }
                },
            )
            FormField(
                label = "偏好内容",
                value = settings.personalizationPrompt,
                onValueChange = { onUpdate { current -> current.copy(personalizationPrompt = it) } },
                singleLine = false,
                minLines = 3,
                supportingText = "例如：更口语一些、不要太正式、会议纪要保留关键动作项。",
            )
            SwitchField(
                label = "自动结构化",
                checked = settings.autoStructureEnabled,
                supportingText = "把较长的口述文本整理成更清晰、更干净的句子或段落。",
                onCheckedChange = {
                    onUpdate { current -> current.copy(autoStructureEnabled = it) }
                },
            )
            SwitchField(
                label = "口语过滤",
                checked = settings.fillerWordFilterEnabled,
                supportingText = "去掉“嗯、啊、然后、你知道”这类没有必要的口头填充词和重复词。",
                onCheckedChange = {
                    onUpdate { current -> current.copy(fillerWordFilterEnabled = it) }
                },
            )
            SwitchField(
                label = "去除结尾句号",
                checked = settings.trimTrailingPeriodEnabled,
                supportingText = "输出结果最后不带句号，更适合聊天和连续输入。",
                onCheckedChange = {
                    onUpdate { current -> current.copy(trimTrailingPeriodEnabled = it) }
                },
            )
            SwitchField(
                label = "加载屏幕识别（Beta）",
                checked = settings.screenContextEnabled,
                supportingText = if (screenContextServiceEnabled) {
                    "已开启屏幕识别权限。开启后，模型会读取当前屏幕可见文本作为上下文参考。"
                } else {
                    "未开启屏幕识别权限。打开后需要再去系统里启用无障碍服务。"
                },
                onCheckedChange = {
                    onUpdate { current -> current.copy(screenContextEnabled = it) }
                },
            )
            if (!screenContextServiceEnabled) {
                ConsoleGuideRow(
                    label = "去开启屏幕识别权限",
                    onClick = onOpenAccessibilitySettings,
                )
            }
        }

        SectionCard(
            title = "豆包模型",
            subtitle = "只在文本优化打开或长按优化全文时使用，统一通过模型规则和提示词完成文本整理。",
            icon = Icons.Outlined.Tune,
        ) {
            SecretField(
                label = "Ark API Key",
                value = settings.arkApiKey,
                onValueChange = { onUpdate { current -> current.copy(arkApiKey = it) } },
                supportingText = "在火山方舟控制台 API Key 管理里创建并复制。",
            )
            ConsoleGuideRow(
                label = "去火山方舟 API Key 管理页面",
                onClick = { openWebPage(context, ARK_API_KEY_CONSOLE_URL) },
            )
            FormField(
                label = "Ark Model ID",
                value = settings.arkModel,
                onValueChange = { onUpdate { current -> current.copy(arkModel = it) } },
                placeholder = AppSettings.DEFAULT_ARK_MODEL_ID,
                supportingText = "默认值是 2.0 Pro 的在线推理 Model ID。这里只使用 Model ID，不再使用 Endpoint ID。",
            )
        }
    }
}

@Composable
private fun SampleEditorCard(
    value: String,
    onValueChange: (String) -> Unit,
) {
    SectionCard(
        title = "输入法手测",
        subtitle = "先把本 App 选成当前输入法，再点下面输入框。系统会弹出悬浮语音球，轻点输入，长按优化当前输入框全文。",
        icon = Icons.Outlined.Science,
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = value,
            onValueChange = onValueChange,
            label = { Text("示例输入框") },
            minLines = 4,
            shape = RoundedCornerShape(18.dp),
        )
    }
}

@Composable
private fun TestingEntryCard(
    onOpenTestingDialog: () -> Unit,
) {
    SectionCard(
        title = "连通性测试",
        subtitle = "主页这里只保留测试入口。点进去会弹出测试窗，语音 SDK 测试和 LLM 结果都在弹窗里查看。",
        icon = Icons.Outlined.Science,
    ) {
        Button(
            onClick = onOpenTestingDialog,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("打开测试弹窗")
        }
    }
}

@Composable
private fun TestingDialogContent(
    speechTest: SpeechTestUiState,
    llmTest: LlmTestUiState,
    onRunSpeechTest: () -> Unit,
    onCopySpeechReport: () -> Unit,
    onLlmInputChange: (String) -> Unit,
    onRunLlmTest: () -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "SDK 语音识别测试",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        StatusChip(
            text = "状态：${speechTest.status}",
            active = speechTest.isRunning || speechTest.finalText.isNotBlank(),
        )
        Button(
            onClick = onRunSpeechTest,
            modifier = Modifier.fillMaxWidth(),
            enabled = !speechTest.isRunning,
        ) {
            Text(if (speechTest.isRunning) "SDK 语音测试中" else "开始 SDK 语音测试")
        }
        TextButton(
            onClick = onCopySpeechReport,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("复制完整调试内容")
        }
        TestResultPanel(
            title = "SDK 测试结果",
            lines = listOfNotNull(
                speechTest.partialText.takeIf { it.isNotBlank() }?.let { "中间结果：$it" },
                speechTest.finalText.takeIf { it.isNotBlank() }?.let { "识别结果：$it" },
                speechTest.error?.let { "错误：$it" },
            ),
            emptyText = "点开始后会用内置 PCM 音频走一次正式的 SDK 识别链路，方便确认当前 Access Token 和内置配置是否可用。",
        )
        TestResultPanel(
            title = "SDK 调试日志",
            lines = listOfNotNull(
                speechTest.debugReport.takeIf { it.isNotBlank() },
                speechTest.logs.takeIf { it.isNotEmpty() && speechTest.debugReport.isBlank() }?.joinToString("\n"),
            ),
            emptyText = "这里会显示 SDK 的初始化日志、识别日志和底层 log tail。复制后可以直接用来定位问题。",
        )
        Text(
            text = "LLM 测试",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        FormField(
            label = "测试文本",
            value = llmTest.inputText,
            onValueChange = onLlmInputChange,
            singleLine = false,
            minLines = 3,
            supportingText = "默认放了一段带语音识别错字的文本，点按钮后只测试豆包模型纠错能力。",
        )
        StatusChip(
            text = "状态：${llmTest.status}",
            active = llmTest.isRunning || llmTest.outputText.isNotBlank(),
        )
        Button(
            onClick = onRunLlmTest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (llmTest.isRunning) "LLM 测试中" else "测试 LLM")
        }
        TestResultPanel(
            title = "LLM 返回",
            lines = listOfNotNull(
                llmTest.outputText.takeIf { it.isNotBlank() }?.let { "输出：$it" },
                llmTest.error?.let { "错误：$it" },
            ),
            emptyText = "这里会显示模型纠错后的文本，便于单独检查 API Key 和 Model ID 是否通。",
        )
    }
}

@Composable
private fun ActionCard(
    onSave: () -> Unit,
    onSaveAndPick: () -> Unit,
) {
    SectionCard(
        title = "操作",
        subtitle = "保存后即可在系统输入法里使用语音输入法。也可以直接保存并打开输入法切换器。",
        icon = Icons.Outlined.Keyboard,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text("保存配置")
            }
            Button(onClick = onSaveAndPick, modifier = Modifier.fillMaxWidth()) {
                Text("保存并切换输入法")
            }
        }
    }
}

@Composable
private fun TestResultPanel(
    title: String,
    lines: List<String>,
    emptyText: String,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (lines.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF64748B),
                )
            } else {
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF0F172A),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xCCFFFFFF)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                if (icon != null) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(title) },
                        colors = AssistChipDefaults.assistChipColors(
                            disabledContainerColor = Color(0xFFFFEDD5),
                            disabledLabelColor = Color(0xFF9A3412),
                        ),
                    )
                } else {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF475569),
                )
                content()
            },
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(title, granted)
        TextButton(onClick = onClick) {
            Text(if (granted) "重新检查" else "去开启")
        }
    }
}

@Composable
private fun StatusChip(text: String, active: Boolean) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(text) },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = if (active) Color(0xFFDCFCE7) else Color(0xFFE2E8F0),
            disabledLabelColor = if (active) Color(0xFF166534) else Color(0xFF334155),
        ),
    )
}

@Composable
private fun ConsoleGuideRow(
    label: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label)
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    minLines: Int = 1,
    placeholder: String? = null,
    supportingText: String? = null,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(18.dp),
    )
}

@Composable
private fun SecretField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    supportingText: String? = null,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        shape = RoundedCornerShape(18.dp),
    )
}

@Composable
private fun SwitchField(
    label: String,
    checked: Boolean,
    supportingText: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun rememberSystemSettingState(provider: () -> Boolean): androidx.compose.runtime.MutableState<Boolean> {
    val state = remember { mutableStateOf(provider()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.value = provider()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return state
}

private fun openInputMethodSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
}

private fun openAccessibilitySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}

private fun showInputMethodPicker(context: Context) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.showInputMethodPicker()
}

private fun openWebPage(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun isImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        ?: return false
    val serviceName = VoiceInputMethodService::class.java.name
    return imm.enabledInputMethodList.any {
        it.packageName == context.packageName && it.serviceName == serviceName
    }
}

private fun isImeSelected(context: Context): Boolean {
    val currentIme = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD,
    ).orEmpty()
    val component = ComponentName(context, VoiceInputMethodService::class.java)
    return currentIme == component.flattenToString() || currentIme == component.flattenToShortString()
}

private fun isScreenContextServiceEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        ?: return false
    val enabledServices = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
    val expectedId = ComponentName(context, ScreenContextAccessibilityService::class.java).flattenToString()
    return enabledServices.any { info ->
        info.resolveInfo.serviceInfo?.let { serviceInfo ->
            "${serviceInfo.packageName}/${serviceInfo.name}" == expectedId
        } == true
    }
}

private fun buildSpeechTestReport(speechTest: SpeechTestUiState): String {
    return buildString {
        append("状态：")
        append(speechTest.status)
        append("\n")
        append("参考文本：")
        append(AppSettings.DEFAULT_SPEECH_TEST_REFERENCE_TEXT)
        append("\n")
        if (speechTest.finalText.isNotBlank()) {
            append("识别结果：")
            append(speechTest.finalText)
            append("\n")
        }
        if (speechTest.partialText.isNotBlank()) {
            append("中间结果：")
            append(speechTest.partialText)
            append("\n")
        }
        if (!speechTest.error.isNullOrBlank()) {
            append("错误：")
            append(speechTest.error)
            append("\n")
        }
        if (speechTest.logs.isNotEmpty()) {
            append("进度日志：\n")
            append(speechTest.logs.joinToString("\n"))
            append("\n")
        }
        if (speechTest.debugReport.isNotBlank()) {
            append("完整调试报告：\n")
            append(speechTest.debugReport)
        }
    }.trim()
}

private const val SPEECH_CONSOLE_URL =
    "https://console.volcengine.com/speech/service/10038?AppID=2586725503"
private const val ARK_API_KEY_CONSOLE_URL =
    "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey?projectName=default"
