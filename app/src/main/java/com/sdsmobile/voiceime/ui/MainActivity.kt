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
import androidx.compose.foundation.layout.weight
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
import androidx.compose.material3.FilterChip
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
import com.sdsmobile.voiceime.model.AsrMode
import com.sdsmobile.voiceime.service.BubbleOverlayService
import com.sdsmobile.voiceime.ui.theme.SdsVoiceImeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                val container = (application as VoiceImeApplication).appContainer
                MainViewModel(container.settingsRepository)
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
                    onModeChange = viewModel::updateMode,
                    onUpdate = viewModel::updateDraft,
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
                text = "点按悬浮球开始或结束语音输入，长按直接修正当前输入框已有文本。",
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
        subtitle = "这类悬浮输入需要悬浮窗、麦克风和无障碍三项能力。",
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
    onModeChange: (AsrMode) -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionCard(
            title = "豆包语音",
            subtitle = "默认按大模型流式识别配置；如你走传统流式 ASR，可切换到标准模式。",
                icon = Icons.Outlined.GraphicEq,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.asrMode == AsrMode.BIG_MODEL,
                    onClick = { onModeChange(AsrMode.BIG_MODEL) },
                    label = { Text("大模型") },
                )
                FilterChip(
                    selected = settings.asrMode == AsrMode.STANDARD,
                    onClick = { onModeChange(AsrMode.STANDARD) },
                    label = { Text("标准 ASR") },
                )
            }
            FormField(
                label = "App ID / App Key",
                value = settings.speechAppId,
                onValueChange = { onUpdate { current -> current.copy(speechAppId = it) } },
            )
            SecretField(
                label = "Speech Token",
                value = settings.speechToken,
                onValueChange = { onUpdate { current -> current.copy(speechToken = it) } },
            )
            if (settings.asrMode == AsrMode.BIG_MODEL) {
                FormField(
                    label = "Resource ID",
                    value = settings.speechResourceId,
                    onValueChange = { onUpdate { current -> current.copy(speechResourceId = it) } },
                )
            } else {
                FormField(
                    label = "Cluster",
                    value = settings.speechCluster,
                    onValueChange = { onUpdate { current -> current.copy(speechCluster = it) } },
                )
            }
            FormField(
                label = "Speech Address",
                value = settings.speechAddress,
                onValueChange = { onUpdate { current -> current.copy(speechAddress = it) } },
            )
            FormField(
                label = "Speech URI",
                value = settings.speechUri,
                onValueChange = { onUpdate { current -> current.copy(speechUri = it) } },
            )
            FormField(
                label = "ASR Request Params JSON",
                value = settings.speechRequestParamsJson,
                onValueChange = { onUpdate { current -> current.copy(speechRequestParamsJson = it) } },
                singleLine = false,
                minLines = 3,
            )
        }

        SectionCard(
            title = "火山方舟纠错",
            subtitle = "识别结束后会先调用模型纠错，再写入当前输入框；长按悬浮球会修正输入框已有内容。",
            icon = Icons.Outlined.Tune,
        ) {
            SecretField(
                label = "Ark API Key",
                value = settings.arkApiKey,
                onValueChange = { onUpdate { current -> current.copy(arkApiKey = it) } },
            )
            FormField(
                label = "Ark Base URL",
                value = settings.arkBaseUrl,
                onValueChange = { onUpdate { current -> current.copy(arkBaseUrl = it) } },
            )
            FormField(
                label = "Model / Endpoint ID",
                value = settings.arkModel,
                onValueChange = { onUpdate { current -> current.copy(arkModel = it) } },
            )
            FormField(
                label = "System Prompt",
                value = settings.correctionPrompt,
                onValueChange = { onUpdate { current -> current.copy(correctionPrompt = it) } },
                singleLine = false,
                minLines = 4,
            )
        }
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
        subtitle = "保存配置后可直接启动悬浮球。启动按钮也会自动保存当前表单。",
        icon = Icons.Outlined.SettingsSuggest,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                Text("保存配置")
            }
            Button(onClick = onToggleBubble, modifier = Modifier.weight(1f)) {
                Text(if (bubbleRunning) "关闭悬浮球" else "启动悬浮球")
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
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
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
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
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
