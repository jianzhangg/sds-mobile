package com.sdsmobile.voiceime.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sampleText by rememberSaveable { mutableStateOf("点击这里测试输入法自动弹出") }
    var audioGranted by rememberSystemSettingState {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }
    var imeEnabled by rememberSystemSettingState { isImeEnabled(context) }
    var imeSelected by rememberSystemSettingState { isImeSelected(context) }

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
                    onAudioClick = { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onOpenImeSettings = { openInputMethodSettings(context) },
                    onShowImePicker = { showInputMethodPicker(context) },
                )

                SettingsCard(
                    settings = uiState.draft,
                    onUpdate = viewModel::updateDraft,
                )

                SampleEditorCard(
                    value = sampleText,
                    onValueChange = { sampleText = it },
                )

                TestingCard(
                    speechTest = uiState.speechTest,
                    llmTest = uiState.llmTest,
                    onRunSpeechTest = viewModel::runSpeechTest,
                    onLlmInputChange = viewModel::updateLlmTestInput,
                    onRunLlmTest = viewModel::runLlmTest,
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
                title = { Text("语音识别测试失败") },
                text = {
                    Text(
                        text = dialogText,
                        style = MaterialTheme.typography.bodySmall,
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
                text = "豆包语音输入法",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "现在按真正输入法实现。点进任意输入框时，系统会显示一个小型语音输入面板，不需要悬浮窗权限，也不依赖无障碍。",
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
    onAudioClick: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
) {
    SectionCard(
        title = "启用步骤",
        subtitle = "这版不再常驻悬浮球。正确流程是启用输入法、切换到本输入法，然后点进输入框自动出现语音面板。",
        icon = Icons.Outlined.Keyboard,
    ) {
        PermissionRow("麦克风权限", audioGranted, onAudioClick)
        PermissionRow("已在系统里启用输入法", imeEnabled, onOpenImeSettings)
        PermissionRow("当前输入法已切换到本 App", imeSelected, onShowImePicker)
    }
}

@Composable
private fun SettingsCard(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionCard(
            title = "豆包语音",
            subtitle = "按豆包流式语音识别 2.0 接入。App ID 和小时版 Resource ID 已固定，只需要填当前账号的 Access Token。",
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
            title = "豆包模型纠错",
            subtitle = "语音结果提交前会先做一轮纠错；在输入法小球上长按会读取当前输入框内容并整体替换。",
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
        subtitle = "先把本 App 选成当前输入法，再点下面输入框。系统会像普通键盘一样自动弹出这个输入法面板。",
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
private fun TestingCard(
    speechTest: SpeechTestUiState,
    llmTest: LlmTestUiState,
    onRunSpeechTest: () -> Unit,
    onLlmInputChange: (String) -> Unit,
    onRunLlmTest: () -> Unit,
) {
    SectionCard(
        title = "连通性测试",
        subtitle = "这里保留独立测试。语音识别测试会直接跑内置 PCM 文件，不依赖现场录音，方便稳定复现问题。",
        icon = Icons.Outlined.Science,
    ) {
        Text(
            text = "语音识别测试",
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
            Text(if (speechTest.isRunning) "语音识别测试中" else "开始内置音频识别测试")
        }
        TestResultPanel(
            title = "识别结果",
            lines = listOfNotNull(
                "参考文本：${AppSettings.DEFAULT_SPEECH_TEST_REFERENCE_TEXT}",
                speechTest.partialText.takeIf { it.isNotBlank() }?.let { "实时文本：$it" },
                speechTest.finalText.takeIf { it.isNotBlank() }?.let { "最终文本：$it" },
                speechTest.error?.let { "错误：$it" },
            ),
            emptyText = "点开始后会直接跑内置 PCM 音频文件，并在这里显示识别结果。",
        )
        TestResultPanel(
            title = "调试日志",
            lines = speechTest.logs,
            emptyText = "这里会显示识别配置、阶段日志和 SDK 日志尾部。出错时也会弹窗显示，方便截图排查。",
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
        subtitle = "保存后就可以去系统里启用这个输入法。你也可以直接保存并打开输入法切换器。",
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

private const val SPEECH_CONSOLE_URL =
    "https://console.volcengine.com/speech/service/10038?AppID=2586725503"
private const val ARK_API_KEY_CONSOLE_URL =
    "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey?projectName=default"
