package com.xbot.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xbot.android.core.AppViewModel
import com.xbot.android.voice.ConversationEntry
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 完整设置页。对应 Flutter settings_screen.dart 的原生等价（暗色 Material3 列表）。
 *
 * 分组：显示模式 / 身份识别 / 语音助手 / Pophie 服务 / 识别功能 / 关于。
 * 改动即时持久化到 [SettingsStore] 并应用到运行中的语音助手 / 视觉管线。
 */
@Composable
fun SettingsScreen(
    controller: MainScreenController,
    appViewModel: AppViewModel,
    onClose: () -> Unit,
    onResetOwner: () -> Unit,
) {
    val store = controller.settingsStore
    val s = store.settings
    val scope = rememberCoroutineScope()

    // 弹窗状态。
    var showLog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var connResult by remember { mutableStateOf<Boolean?>(null) }
    var testing by remember { mutableStateOf(false) }

    if (showLog) {
        ConversationLogScreen(controller, onBack = { showLog = false })
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF15151A))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                    )
                }
                Text("设置", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                // —— 显示模式 ——
                SettingsSection("显示模式", "关闭时显示虚拟形象；开启后切换到摄像头识别画面（调试人脸/手势）。") {
                    SwitchRow("调试模式", s.debugMode) { v ->
                        store.update { it.copy(debugMode = v) }
                    }
                }

                // —— 身份识别 ——
                SettingsSection("身份识别") {
                    InfoRow("主人", appViewModel.ownerProfileStore.profile?.nickname ?: "—")
                    InfoRow("认识我", "已录入 ${appViewModel.personRepository.people.size} 人")
                    SwitchRow("启用身份识别", s.identityEnabled) { v ->
                        store.update { it.copy(identityEnabled = v) }
                    }
                    NavRow("重新设置主人", "清除主人信息并重新进入引导") { confirmReset = true }
                }

                // —— 语音助手 ——
                SettingsSection(
                    "语音助手",
                    "开启后可双击触发或语音唤醒并对话。唤醒词本地离线检测，识别与对话走云端。",
                ) {
                    SwitchRow("启用语音助手", s.voiceEnabled) { v ->
                        store.update { it.copy(voiceEnabled = v) }
                        controller.onVoiceEnabledChanged()
                    }
                    SwitchRow("语音唤醒", s.wakeWordEnabled, enabled = s.voiceEnabled) { v ->
                        store.update { it.copy(wakeWordEnabled = v) }
                        controller.applyVoiceSettings()
                    }
                    NavRow("唤醒词", s.wakeWord, enabled = s.voiceEnabled && s.wakeWordEnabled) {
                        editTarget = EditTarget.WakeWord
                    }
                    SwitchRow("语音播报", s.ttsEnabled, enabled = s.voiceEnabled) { v ->
                        store.update { it.copy(ttsEnabled = v) }
                        controller.applyVoiceSettings()
                    }
                    SwitchRow("流式对话模式", s.streamingSttEnabled, enabled = s.voiceEnabled) { v ->
                        store.update { it.copy(streamingSttEnabled = v) }
                        controller.applyVoiceSettings()
                    }
                }

                // —— Pophie 服务 ——
                SettingsSection(
                    "Pophie 服务",
                    "语音对话走 Pophie 后端，一次完成识别+大模型+合成。请填写后端地址（局域网用电脑 IP）。",
                ) {
                    NavRow("后端地址", s.baseUrl) { editTarget = EditTarget.BaseUrl }
                    NavRow("TTS 音色", s.voiceId.ifBlank { "默认音色" }) { editTarget = EditTarget.VoiceId }
                    InfoRow("设备 ID", s.robotId)
                    NavRow("测试连接", if (testing) "检测中…" else "检查后端是否可达") {
                        if (!testing) {
                            testing = true
                            connResult = null
                            scope.launch {
                                val va = controller.ensureVoiceAssistant()
                                va.pophie.config = va.pophie.config.copy(baseUrl = store.settings.baseUrl)
                                val ok = va.pophie.health()
                                testing = false
                                connResult = ok
                            }
                        }
                    }
                    NavRow("交互日志", "查看语音对话各阶段记录") { showLog = true }
                }

                // —— 识别功能 ——
                SettingsSection("识别功能") {
                    SwitchRow("人脸表情识别", s.faceEnabled) { v ->
                        store.update { it.copy(faceEnabled = v) }
                    }
                    SwitchRow("手势识别", s.handEnabled) { v ->
                        store.update { it.copy(handEnabled = v) }
                    }
                    SwitchRow("物体识别", s.objectEnabled) { v ->
                        store.update { it.copy(objectEnabled = v) }
                    }
                }

                // —— 关于 ——
                SettingsSection("关于") {
                    InfoRow("版本", "1.0.0")
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // —— 弹窗：编辑 ——
    editTarget?.let { target ->
        val (title, initial) = when (target) {
            EditTarget.WakeWord -> "设置唤醒词" to s.wakeWord
            EditTarget.BaseUrl -> "后端地址 (Base URL)" to s.baseUrl
            EditTarget.VoiceId -> "TTS 音色 ID（空=默认）" to s.voiceId
        }
        TextEditDialog(
            title = title,
            initial = initial,
            onConfirm = { text ->
                when (target) {
                    EditTarget.WakeWord -> {
                        val kw = text.trim()
                        if (kw.isNotEmpty()) {
                            store.update { it.copy(wakeWord = kw) }
                            controller.applyVoiceSettings()
                        }
                    }
                    EditTarget.BaseUrl -> {
                        val url = text.trim().trimEnd('/')
                        if (url.isNotEmpty()) {
                            store.update { it.copy(baseUrl = url) }
                            controller.applyVoiceSettings()
                        }
                    }
                    EditTarget.VoiceId -> {
                        store.update { it.copy(voiceId = text.trim()) }
                        controller.applyVoiceSettings()
                    }
                }
                editTarget = null
            },
            onDismiss = { editTarget = null },
        )
    }

    // —— 弹窗：测试连接结果 ——
    connResult?.let { ok ->
        AlertDialog(
            onDismissRequest = { connResult = null },
            confirmButton = { TextButton(onClick = { connResult = null }) { Text("好") } },
            title = { Text(if (ok) "连接成功" else "连接失败") },
            text = {
                Text(
                    if (ok) "后端可达，语音能力已就绪。"
                    else "无法连接后端，请检查地址与网络（同一局域网、防火墙、端口 8000）。",
                )
            },
        )
    }

    // —— 弹窗：重设主人确认 ——
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onResetOwner()
                }) { Text("重新设置", color = Color(0xFFFF453A)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
            title = { Text("重新设置主人") },
            text = { Text("将清除主人信息（含人脸）并重新进入引导。此操作不可撤销，确定继续吗？") },
        )
    }
}

private enum class EditTarget { WakeWord, BaseUrl, VoiceId }

@Composable
private fun SettingsSection(
    title: String,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Text(
        title,
        color = Color(0xFF9A9AA0),
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF15151A), RoundedCornerShape(12.dp))
            .padding(vertical = 4.dp),
    ) {
        content()
    }
    if (footer != null) {
        Text(
            footer,
            color = Color(0xFF6A6A70),
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    value: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (enabled) Color.White else Color(0xFF6A6A70),
            modifier = Modifier.weight(1f),
        )
        Switch(checked = value, onCheckedChange = if (enabled) onChange else null, enabled = enabled)
    }
}

@Composable
private fun NavRow(label: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (enabled) it.clickable { onClick() } else it }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (enabled) Color.White else Color(0xFF6A6A70),
            modifier = Modifier.width(96.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            color = Color(0xFF9A9AA0),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (enabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(0xFF6A6A70),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, modifier = Modifier.width(96.dp))
        Spacer(Modifier.width(8.dp))
        Text(value, color = Color(0xFF9A9AA0), fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
    }
}

@Composable
private fun TextEditDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
            )
        },
    )
}

/** 交互日志页：倒序展示对话各阶段记录，错误红色标记，可清空。 */
@Composable
private fun ConversationLogScreen(controller: MainScreenController, onBack: () -> Unit) {
    val entries = controller.conversationLog.entries
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF15151A))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                    )
                }
                Text("交互日志", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { controller.conversationLog.clear() }) { Text("清空") }
            }
            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无记录\n双击屏幕或唤醒「你好小白」开始一轮对话",
                        color = Color(0xFF9A9AA0),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(entries.reversed()) { e ->
                        LogRow(e, fmt)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(e: ConversationEntry, fmt: SimpleDateFormat) {
    val color = stageColor(e.stage, e.error)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF15151A), RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp, end = 8.dp)
                .size(8.dp)
                .background(color, RoundedCornerShape(4.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.stage, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(fmt.format(Date(e.timestamp)), color = Color(0xFF6A6A70), fontSize = 11.sp)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                e.message,
                color = if (e.error) Color(0xFFFF9D8A) else Color.White,
                fontSize = 13.sp,
            )
        }
    }
}

private fun stageColor(stage: String, error: Boolean): Color {
    if (error) return Color(0xFFFF453A)
    return when (stage) {
        "wake" -> Color(0xFFFFD60A)
        "listen" -> Color(0xFF64D2FF)
        "think" -> Color(0xFFBF5AF2)
        "speak" -> Color(0xFF30D158)
        "end" -> Color(0xFF8E8E93)
        else -> Color(0xFF9A9AA0)
    }
}
