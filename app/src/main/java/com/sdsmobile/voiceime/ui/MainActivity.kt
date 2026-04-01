package com.sdsmobile.voiceime.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material.icons.outlined.Tune
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
import com.sdsmobile.voiceime.service.BubbleOverlayService
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
    var overlayGranted by rememberSystemSettingState { Settings.canDrawOverlays(context) }
    var audioGranted by rememberSystemSettingState {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }
    var notificationsGranted by rememberSystemSettingState {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        audioGranted = granted
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsGranted = granted
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
                    bubbleRunning = uiState.bubbleRunning,
                    accessibilityConnected = uiState.accessibilityConnected,
                )

                PermissionCard(
                    overlayGranted = overlayGranted,
                    audioGranted = audioGranted,
                    notificationsGranted = notificationsGranted,
                    accessibilityConnected = uiState.accessibilityConnected,
                    onOverlayClick = { openOverlayPermission(context) },
                    onAccessibilityClick = { openAccessibilitySettings(context) },
                    onAudioClick = { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onNotificationClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )

                SettingsCard(
                    settings = uiState.draft,
                    onUpdate = viewModel::updateDraft,
                )

                TestingCard(
                    speechTest = uiState.speechTest,
                    llmTest = uiState.llmTest,
                    onToggleSpeechTest = { viewModel.toggleSpeechTest(audioGranted) },
                    onLlmInputChange = viewModel::updateLlmTestInput,
                    onRunLlmTest = viewModel::runLlmTest,
                )

                ActionCard(
                    bubbleRunning = uiState.bubbleRunning,
                    onSave = {
                        scope.launch {
                            viewModel.persistDraft()
                        }
                    },
                    onToggleBubble = {
                        scope.launch {
                            viewModel.persistDraft()
                            if (uiState.bubbleRunning) {
                                BubbleOverlayService.stop(context)
                            } else {
                                BubbleOverlayService.start(context)
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    bubbleRunning: Boolean,
    accessibilityConnected: Boolean,
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
                text = "豆包语音悬浮输入",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "配置只保留豆包语音和豆包模型两段参数。点按悬浮球开始或结束输入，长按会修正当前输入框已有文本。",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFD1D5DB),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    text = if (bubbleRunning) "悬浮球已运行" else "悬浮球未启动",
                    active = bubbleRunning,
                )
                StatusChip(
                    text = if (accessibilityConnected) "无障碍已连接" else "无障碍未连接",
                    active = accessibilityConnected,
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    overlayGranted: Boolean,
    audioGranted: Boolean,
    notificationsGranted: Boolean,
    accessibilityConnected: Boolean,
    onOverlayClick: () -> Unit,
    onAccessibilityClick: () -> Unit,
    onAudioClick: () -> Unit,
    onNotificationClick: () -> Unit,
) {
    SectionCard(
        title = "系统开关",
        subtitle = "悬浮输入需要悬浮窗、麦克风和无障碍三项能力，通知权限只用于前台服务常驻。",
    ) {
        PermissionRow("悬浮窗", overlayGranted, onOverlayClick)
        PermissionRow("麦克风", audioGranted, onAudioClick)
        PermissionRow("通知", notificationsGranted, onNotificationClick)
        PermissionRow("无障碍服务", accessibilityConnected, onAccessibilityClick)
    }
}

@Composable
private fun SettingsCard(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionCard(
            title = "豆包语音",
            subtitle = "按豆包流式语音识别 2.0 接入，只需要填 App ID、Access Token 和 Resource ID。地址、URI 和请求参数已内置。",
            icon = Icons.Outlined.GraphicEq,
        ) {
            FormField(
                label = "App ID",
                value = settings.speechAppId,
                onValueChange = { onUpdate { current -> current.copy(speechAppId = it) } },
                supportingText = "默认带入当前控制台里的 APP ID，可按需改成别的应用。",
            )
            SecretField(
                label = "Access Token",
                value = settings.speechToken,
                onValueChange = { onUpdate { current -> current.copy(speechToken = it) } },
                supportingText = "对应豆包语音控制台“服务接口认证信息”里的 Access Token。",
            )
            FormField(
                label = "Resource ID / 实例 ID",
                value = settings.speechResourceId,
                onValueChange = { onUpdate { current -> current.copy(speechResourceId = it) } },
                placeholder = AppSettings.EXAMPLE_SPEECH_RESOURCE_ID,
                supportingText = "可直接填控制台实例名，例如上面的 Doubao_Seed_ASR_Streaming_2.0...",
            )
        }

        SectionCard(
            title = "豆包模型纠错",
            subtitle = "识别结束后会用豆包模型把文本修顺，再写入输入框。这里也只保留 API Key 和模型接入点两个字段。",
            icon = Icons.Outlined.Tune,
        ) {
            SecretField(
                label = "Ark API Key",
                value = settings.arkApiKey,
                onValueChange = { onUpdate { current -> current.copy(arkApiKey = it) } },
                supportingText = "在火山方舟控制台 API Key 管理里创建并复制。",
            )
            FormField(
                label = "Endpoint ID / Model ID",
                value = settings.arkModel,
                onValueChange = { onUpdate { current -> current.copy(arkModel = it) } },
                supportingText = "按官方接入方式，优先填写在线推理的 Endpoint ID；调用基础模型时也可填可用的 model ID。",
            )
        }
    }
}

@Composable
private fun TestingCard(
    speechTest: SpeechTestUiState,
    llmTest: LlmTestUiState,
    onToggleSpeechTest: () -> Unit,
    onLlmInputChange: (String) -> Unit,
    onRunLlmTest: () -> Unit,
) {
    SectionCard(
        title = "连通性测试",
        subtitle = "先测豆包语音 SDK，再单独测方舟 LLM。两个测试都只在当前页面展示结果，不会写入别的 App。",
        icon = Icons.Outlined.SettingsSuggest,
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
            onClick = onToggleSpeechTest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (speechTest.isRunning) "结束语音识别测试" else "开始语音识别测试")
        }
        TestResultPanel(
            title = "识别结果",
            lines = listOfNotNull(
                speechTest.partialText.takeIf { it.isNotBlank() }?.let { "实时文本：$it" },
                speechTest.finalText.takeIf { it.isNotBlank() }?.let { "最终文本：$it" },
                speechTest.error?.let { "错误：$it" },
            ),
            emptyText = "点开始后会直接录音，结束后在这里显示实时结果和最终结果。",
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
            emptyText = "这里会显示模型纠错后的文本，便于单独检查 API Key 和 Endpoint ID 是否通。",
        )
    }
}

@Composable
private fun ActionCard(
    bubbleRunning: Boolean,
    onSave: () -> Unit,
    onToggleBubble: () -> Unit,
) {
    SectionCard(
        title = "操作",
        subtitle = "保存配置后可直接启动悬浮球。启动按钮也会先保存当前表单。",
        icon = Icons.Outlined.SettingsSuggest,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text("保存配置")
            }
            Button(onClick = onToggleBubble, modifier = Modifier.fillMaxWidth()) {
                Text(if (bubbleRunning) "关闭悬浮球" else "启动悬浮球")
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

private fun openOverlayPermission(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
    context.startActivity(intent)
}

private fun openAccessibilitySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}
