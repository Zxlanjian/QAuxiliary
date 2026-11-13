/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * 新增功能：文件上传重命名（从 TimTool 移植，私聊侧）
 *
 * 原实现：TimTool/app/src/main/java/top/sacz/timtool/hook/item/file/FileUploadRename.kt
 *         @HookItem("辅助功能/文件/文件上传重命名")
 *
 * 和 QA 已有 BaseApk（"重命名 APK"）的分工：
 *   BaseApk      只处理**群文件**发送路径（DexKit 目标 TroopSendFile_QQNT）
 *   本功能       补上**私聊/C2C** 发送路径（NTFileManageBridger），两者互补不冲突
 *
 * TIM 4.1.0 适配说明（classes.dex，已用 baksmali 核对）：
 *   Lcom/tencent/mobileqq/filemanager/nt/NTFileManageBridger;
 *     private synthetic s0(LFileManagerEntity;Ljava/lang/Runnable;Ljava/lang/String;Ljava/lang/String;)V
 *     public  synthetic t0(LFileManagerEntity;Ljava/lang/Runnable;Ljava/lang/String;Ljava/lang/String;)V
 *         -> invoke-direct {p0, p1, p2, p3, p4}, ...->s0(...)V
 *
 *   Lcom/tencent/mobileqq/filemanager/data/FileManagerEntity;
 *     .field public fileName:Ljava/lang/String;
 *     .field public strFilePath:Ljava/lang/String;
 *
 *   私聊发文件最终都会走 s0()/t0()，在这里把 fileName 改掉即可。
 *   注意 t0() 只是转发到 s0()，两个都 hook 会重复执行，所以只挂 s0()，
 *   找不到时再退回 t0()。
 */
package cc.ioctl.hook.misc

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import cc.ioctl.util.HostInfo
import cc.ioctl.util.hookAfterIfEnabled
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.classExists
import io.github.qauxv.util.xpcompat.XposedHelpers
import java.io.File

@FunctionHookEntry
@UiItemAgentEntry
object FileUploadRenameHook : CommonSwitchFunctionHook("timas_file_upload_rename") {

    private const val CLS_BRIDGER = "com.tencent.mobileqq.filemanager.nt.NTFileManageBridger"
    private const val CLS_ENTITY = "com.tencent.mobileqq.filemanager.data.FileManagerEntity"

    /** base.apk / base(1).apk 这类被系统改名的安装包 */
    private val BASE_APK = Regex("^base(\\([0-9]+\\))?\\.apk$", RegexOption.IGNORE_CASE)

    override val name: String = "文件上传重命名"

    override val description: String =
        "私聊发送安装包时自动重命名：base.apk -> 应用名_版本.APK，并把 .apk 后缀改成 .APK，避免被宿主改名成 .apk.1"

    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Auxiliary.FILE_CATEGORY

    override val isAvailable: Boolean = classExists(CLS_BRIDGER) && classExists(CLS_ENTITY)

    override fun initOnce(): Boolean {
        return try {
            val bridger = Initiator.loadClass(CLS_BRIDGER)
            val entity = Initiator.loadClass(CLS_ENTITY)

            // (FileManagerEntity, Runnable, String, String)V
            val candidates = bridger.declaredMethods.filter { m ->
                m.returnType == Void.TYPE &&
                        m.parameterTypes.size == 4 &&
                        m.parameterTypes[0] == entity &&
                        m.parameterTypes[1] == Runnable::class.java
            }
            // 优先 private 的 s0（真正的实现），其次 public 的 t0（转发壳）
            val target = candidates.firstOrNull { it.name == "s0" }
                ?: candidates.firstOrNull { !java.lang.reflect.Modifier.isPublic(it.modifiers) }
                ?: candidates.firstOrNull()
                ?: return false

            val fileNameField = entity.getField("fileName")
            val filePathField = entity.getField("strFilePath")

            hookAfterIfEnabled(target) { param ->
                try {
                    val obj = param.args[0] ?: return@hookAfterIfEnabled
                    val name = fileNameField.get(obj) as? String ?: return@hookAfterIfEnabled
                    val path = filePathField.get(obj) as? String

                    val newName = resolveName(name, path) ?: return@hookAfterIfEnabled
                    if (newName != name) {
                        fileNameField.set(obj, newName)
                    }
                } catch (_: Throwable) {
                    // 改不动就算了，绝对不能因为重命名失败让发送崩掉
                }
            }
            true
        } catch (t: Throwable) {
            traceError(t)
            false
        }
    }

    private fun resolveName(fileName: String, filePath: String?): String? {
        // 1) base.apk 系列：用安装包信息重命名
        if (BASE_APK.matches(fileName)) {
            val info = readApkInfo(filePath) ?: return null
            val label = runCatching {
                info.loadLabel(HostInfo.getApplication().packageManager).toString()
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: info.packageName
            return "$label.APK"
        }
        // 2) 普通 .apk：只把后缀大写，防止宿主追加 .1
        if (fileName.endsWith(".apk", ignoreCase = true) && !fileName.endsWith(".APK")) {
            return fileName.dropLast(4) + ".APK"
        }
        return null
    }

    private fun readApkInfo(filePath: String?): ApplicationInfo? {
        if (filePath.isNullOrBlank() || !File(filePath).isFile) {
            return null
        }
        return runCatching {
            val pm: PackageManager = HostInfo.getApplication().packageManager
            val archive = pm.getPackageArchiveInfo(filePath, 0) ?: return null
            archive.applicationInfo?.also {
                it.sourceDir = filePath
                it.publicSourceDir = filePath
            }
        }.getOrNull()
    }
}
