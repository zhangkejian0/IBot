package com.xbot.android.core

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.xbot.android.model.EnrollCapture
import com.xbot.android.model.OwnerProfile
import com.xbot.android.model.Person
import com.xbot.android.store.OwnerProfileStore
import com.xbot.android.store.PersonRepository
import com.xbot.android.store.SettingsStore
import com.xbot.android.vision.FaceLandmarkEngine
import com.xbot.android.vision.FaceRecognizer
import com.xbot.android.vision.ImageUtils
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * 应用核心 ViewModel：管理 AppPhase + 主人档案/人物库 + 录入采样。
 *
 * 对应 Flutter AppController 的「阶段分流 + 主人档案 + 录入」部分（不含相机取流，
 * 相机由 MainScreenController 在 ready 阶段持有）。
 *
 * - 加载阶段（loading）：初始化 PersonRepository / OwnerProfileStore，决定进 onboarding 还是 ready。
 * - 录入采样：向导的人脸步骤调 [requestEnrollCapture]，下一帧检测到主脸即裁剪 + embed。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "AppViewModel"
        private const val FACE_MODEL_PATH = "face_landmarker.task"
        private const val MOBILEFACENET_PATH = "mobilefacenet.tflite"
    }

    val personRepository = PersonRepository(app)
    val ownerProfileStore = OwnerProfileStore(app)
    val settingsStore = SettingsStore(app)

    /** 录入用的人脸引擎 + 身份识别（与主界面引擎独立，避免抢占）。 */
    private val enrollFaceEngine = FaceLandmarkEngine(app, FACE_MODEL_PATH)
    private val faceRecognizer = FaceRecognizer(app, MOBILEFACENET_PATH)

    /** 当前阶段。 */
    val phase = AtomicReference(AppPhase.LOADING)

    /** 是否可录入（人脸引擎 + MobileFaceNet 均就绪）。 */
    val canEnroll: Boolean get() = enrollFaceEngine.isReady && faceRecognizer.isAvailable

    /** 录入采样请求：非 null 表示向导在等待一张主脸采样。 */
    private val enrollRequest = AtomicReference<EnrollRequest?>(null)

    private data class EnrollRequest(val onCapture: (EnrollCapture?) -> Unit)

    /** 初始化：加载人物库 + 主人档案，分流阶段。 */
    fun initialize() {
        try {
            personRepository.load()
            ownerProfileStore.load()
            settingsStore.load()
            phase.set(if (ownerProfileStore.isRegistered) AppPhase.READY else AppPhase.ONBOARDING)
        } catch (e: Exception) {
            Log.e(TAG, "初始化失败: ${e.message}")
            phase.set(AppPhase.ERROR)
        }
    }

    /**
     * 请求一次人脸采样（用于录入）。下一帧检测到主脸即裁剪 + embed 回调。
     * 调用方应在向导的人脸步骤持续把当前相机帧喂给 [tryEnrollFrame]，
     * 直到回调被触发或超时。
     */
    fun requestEnrollCapture(onCapture: (EnrollCapture?) -> Unit) {
        enrollRequest.set(EnrollRequest(onCapture))
    }

    /**
     * 喂入一帧 bitmap 供录入采样（向导人脸步骤调用）。
     * 检测到主脸 → 裁剪 → embed → 触发回调并清除请求。
     */
    fun tryEnrollFrame(bitmap: Bitmap) {
        val req = enrollRequest.get() ?: return
        // 诊断：记录每帧是否检测到主脸（便于定位「采不到样本」问题）。
        // 节流：仅当有 pending 请求时才检测，避免无谓推理。
        val face = enrollFaceEngine.detect(bitmap)
        if (face == null) {
            Log.d(TAG, "录入：未检测到人脸（bitmap ${bitmap.width}x${bitmap.height}）")
            return
        }
        try {
            val crop = ImageUtils.cropNormalized(bitmap, face.boundingBox)
            val embedding = faceRecognizer.embed(crop)
            Log.i(TAG, "录入：检测到人脸，embed=${if (embedding != null) "成功(${embedding.size}维)" else "失败"}")
            // 编码 jpg（质量 90）。
            val baos = java.io.ByteArrayOutputStream()
            crop.compress(Bitmap.CompressFormat.JPEG, 90, baos)
            val jpgBytes = baos.toByteArray()
            enrollRequest.set(null)
            req.onCapture(EnrollCapture(jpgBytes, embedding))
        } catch (e: Exception) {
            Log.e(TAG, "录入采样失败: ${e.message}")
        }
    }

    /**
     * 完成首次激活向导。对应 Flutter completeOnboarding。
     *
     * 若带人脸样本：构造主脸 Person（relation=owner），填 embeddings，存头像 + 存人物，
     * 回填 profile.personId / faceRegistered。本地立即生效进 ready（不阻塞于网络）。
     */
    fun completeOnboarding(profile: OwnerProfile, faceSamples: List<EnrollCapture>?) {
        var p = profile.copy(syncedToServer = false)
        if (!faceSamples.isNullOrEmpty()) {
            val valid = faceSamples.filter { it.hasEmbedding }
            if (valid.isNotEmpty()) {
                val person = Person(
                    id = System.currentTimeMillis().toString(),
                    name = profile.nickname,
                    relation = com.xbot.android.model.FamilyRelation.OWNER,
                )
                person.embeddings.addAll(valid.mapNotNull { it.embedding })
                val avatarPath = saveAvatar(person.id, valid.first().jpgBytes)
                person.avatarPath = avatarPath
                personRepository.upsert(person)
                p = p.copy(personId = person.id, faceRegistered = true)
            }
        }
        ownerProfileStore.save(p)
        phase.set(AppPhase.READY)
    }

    /** 重置主人：重新进入向导。对应 Flutter resetOwner。 */
    fun resetOwner() {
        val ownerPersonId = ownerProfileStore.profile?.personId
        ownerProfileStore.clear()
        if (ownerPersonId != null) personRepository.delete(ownerPersonId)
        phase.set(AppPhase.ONBOARDING)
    }

    private fun saveAvatar(personId: String, jpg: ByteArray): String {
        val dir = personRepository.avatarsDir()
        val file = File(dir, "$personId.jpg")
        FileOutputStream(file).use { it.write(jpg) }
        return file.absolutePath
    }

    override fun onCleared() {
        enrollFaceEngine.close()
        faceRecognizer.close()
        super.onCleared()
    }
}

/** 工厂（AndroidViewModel 需要 Application 构造）。 */
class AppViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(app) as T
}
