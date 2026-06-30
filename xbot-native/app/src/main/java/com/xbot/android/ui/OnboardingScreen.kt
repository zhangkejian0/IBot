package com.xbot.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.xbot.android.core.AppViewModel
import com.xbot.android.model.EnrollCapture
import com.xbot.android.model.Gender
import com.xbot.android.model.OwnerProfile
import java.util.concurrent.Executors

/**
 * 首次激活向导（5 步）。对应 Flutter OnboardingScreen。
 *
 * 0. 欢迎
 * 1. 关于你：昵称（必填）/性别/生日
 * 2. 机器人昵称（默认「狗蛋」）
 * 3. 人脸录入：采若干样本（captureFaceSample）
 * 4. 总结 → completeOnboarding → 进 ready
 */
@Composable
fun OnboardingScreen(
    appViewModel: AppViewModel,
    onComplete: () -> Unit,
) {
    val totalSteps = 5
    var step by remember { mutableStateOf(0) }

    // 表单暂存。
    var nickname by remember { mutableStateOf("") }
    var robotName by remember { mutableStateOf("狗蛋") }
    var gender by remember { mutableStateOf<Gender?>(null) }
    var birthday by remember { mutableStateOf("") } // yyyy-MM-dd
    // 人脸样本。
    val faceSamples = remember { mutableStateListOf<EnrollCapture>() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 处理键盘弹出时内容上推，避免输入框被遮挡。
            .windowInsetsPadding(WindowInsets.ime)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
            // 进度指示点。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                for (i in 0 until totalSteps) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (i == step) 10.dp else 8.dp)
                            .background(
                                color = if (i <= step) MaterialTheme.colorScheme.primary else Color.Gray,
                                shape = CircleShape,
                            ),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            // 步骤内容（占据剩余空间，底部按钮固定在下方，不重叠）。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopStart,
            ) {
                when (step) {
                    0 -> WelcomeStep()
                    1 -> AboutYouStep(
                        nickname = nickname, onNickname = { nickname = it },
                        gender = gender, onGender = { gender = it },
                        birthday = birthday, onBirthday = { birthday = it },
                    )
                    2 -> RobotNameStep(robotName = robotName, onRobotName = { robotName = it })
                    3 -> FaceScanStep(appViewModel = appViewModel, samples = faceSamples)
                    4 -> SummaryStep(
                        nickname = nickname, robotName = robotName,
                        gender = gender, birthday = birthday,
                        faceCount = faceSamples.size,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // 导航按钮（固定在底部，独立 Row，不与上方内容重叠）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (step > 0) {
                    OutlinedButton(onClick = { step-- }) { Text("上一步") }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Button(
                    onClick = {
                        if (step < totalSteps - 1) {
                            step++
                        } else {
                            // 完成。
                            appViewModel.completeOnboarding(
                                profile = OwnerProfile(
                                    nickname = nickname.ifBlank { "主人" },
                                    robotName = robotName.ifBlank { "狗蛋" },
                                    gender = gender,
                                    birthday = birthday.ifBlank { null },
                                ),
                                faceSamples = faceSamples.toList(),
                            )
                            onComplete()
                        }
                    },
                    // 第一步需昵称非空才可继续。
                    enabled = step != 1 || nickname.isNotBlank(),
                ) {
                    Text(if (step == totalSteps - 1) "完成" else "下一步")
                }
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, subtitle: String = "") {
    Column {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = Color.Gray, fontSize = 13.sp)
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("👋", fontSize = 56.sp)
        Spacer(Modifier.height(16.dp))
        StepTitle("欢迎使用 XBot", "让我们花一分钟认识彼此")
    }
}

@Composable
private fun AboutYouStep(
    nickname: String, onNickname: (String) -> Unit,
    gender: Gender?, onGender: (Gender?) -> Unit,
    birthday: String, onBirthday: (String) -> Unit,
) {
    Column {
        StepTitle("关于你", "告诉我一些关于你的事")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = nickname, onValueChange = onNickname,
            label = { Text("你的称呼 *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = textFieldColors(),
        )
        Spacer(Modifier.height(16.dp))
        Text("性别", color = Color.White)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Gender.entries.forEach { g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = gender == g,
                        onClick = { onGender(if (gender == g) null else g) },
                    )
                    Text(g.label, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = birthday, onValueChange = onBirthday,
            label = { Text("生日（如 1995-06-30，可选）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = textFieldColors(),
        )
    }
}

@Composable
private fun RobotNameStep(robotName: String, onRobotName: (String) -> Unit) {
    Column {
        StepTitle("给我起个名字", "你想叫我什么？")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = robotName, onValueChange = onRobotName,
            label = { Text("机器人昵称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = textFieldColors(),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "你好，我是 ${robotName.ifBlank { "狗蛋" }}，很高兴认识你！",
            color = Color.White,
        )
    }
}

@Composable
private fun FaceScanStep(appViewModel: AppViewModel, samples: MutableList<EnrollCapture>) {
    val context = LocalContext.current
    var hasCamera by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("请正对摄像头…") }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCamera = granted }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        hasCamera = granted
        if (!granted) permLauncher.launch(Manifest.permission.CAMERA)
    }

    // 请求一次采样；收到后加入列表并再请求一次（采 3 张）。
    LaunchedEffect(hasCamera, samples.size) {
        if (!hasCamera || !appViewModel.canEnroll) return@LaunchedEffect
        if (samples.size >= 3) { status = "已采集 ${samples.size} 张，可继续"; return@LaunchedEffect }
        status = "采集中…（${samples.size}/3）"
        appViewModel.requestEnrollCapture { capture ->
            if (capture != null && capture.hasEmbedding) {
                samples.add(capture)
            }
        }
    }

    // 后台帧分析 executor：把每帧喂给 tryEnrollFrame 驱动录入采样。
    val analysisExecutor = remember {
        Executors.newSingleThreadExecutor { Thread(it, "xbot-enroll").apply { isDaemon = true } }
    }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    Row(modifier = Modifier.fillMaxSize()) {
        // 左：可见相机预览（Preview use case）+ 同时绑 ImageAnalysis 喂帧。
        // active = hasCamera：权限授予后 hasCamera 由 false→true，触发 CameraPreview 重新绑定，
        // 确保 ImageAnalysis 的 analyzer 真正挂上（否则采集中永远不动）。
        CameraPreview(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            useFront = true,
            active = hasCamera,
            analysisExecutor = analysisExecutor,
            analyzer = { image ->
                val bmp = image.toBitmapOrNull()
                if (bmp != null) appViewModel.tryEnrollFrame(bmp)
                image.close()
            },
        )
        Spacer(Modifier.width(16.dp))
        // 右：说明与采集状态。
        Column(
            modifier = Modifier.width(220.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            StepTitle("认识我", "对着摄像头采集人脸")
            Spacer(Modifier.height(16.dp))
            Text(
                status,
                color = if (samples.size >= 3) MaterialTheme.colorScheme.primary else Color.White,
            )
            if (!hasCamera) {
                Text("等待摄像头权限…", color = Color.Gray)
            }
            if (samples.size >= 3) {
                Spacer(Modifier.height(8.dp))
                Text("✅ 录入完成（${samples.size} 张）", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** ImageProxy → Bitmap（nullable；失败返回 null）。 */
private fun ImageProxy.toBitmapOrNull(): android.graphics.Bitmap? = try {
    toBitmap()
} catch (_: Exception) {
    null
}

@Composable
private fun SummaryStep(
    nickname: String, robotName: String,
    gender: Gender?, birthday: String, faceCount: Int,
) {
    Column {
        StepTitle("就绪", "确认信息并完成")
        Spacer(Modifier.height(24.dp))
        SummaryRow("你的称呼", nickname.ifBlank { "主人" })
        SummaryRow("机器人昵称", robotName.ifBlank { "狗蛋" })
        SummaryRow("性别", gender?.label ?: "未填写")
        SummaryRow("生日", birthday.ifBlank { "未填写" })
        SummaryRow("人脸", if (faceCount > 0) "已录入（$faceCount 张）" else "未录入")
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.Gray)
        Text(value, color = Color.White)
    }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = Color.Gray,
    cursorColor = MaterialTheme.colorScheme.primary,
)
