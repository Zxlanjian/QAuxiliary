/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * 新增功能：每天自动备份模块数据（从 TimTool 移植）
 *
 * 原实现：TimTool/app/src/main/java/top/sacz/timtool/hook/item/file/AutoBackupModuleData.kt
 *         @HookItem("辅助功能/实验性/每天自动备份模块数据")
 *
 * 与 TimTool 的差异：
 *   1. TimTool 自己遍历目录打 zip；QA 已经有官方 BackupConfigSession
 *      （io.github.qauxv.config.BackupConfigSession），直接复用，避免重复实现 MMKV 的 .crc 处理。
 *   2. 备份目录用 getExternalFilesDir(null)/qa_auto_backup/，不需要存储权限；
 *      TimTool 写到 /sdcard/Download 在 Android 11+ 需要 MANAGE_EXTERNAL_STORAGE。
 *   3. 只保留最近 7 份，避免无限膨胀。
 *
 * 本功能不 hook 宿主任何方法，纯模块侧定时任务，所以 TIM/QQ 全版本可用。
 */
package cc.ioctl.hook.misc

import cc.ioctl.util.HostInfo
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.config.BackupConfigSession
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.SyncUtils
import me.ketal.data.ConfigData
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@FunctionHookEntry
@UiItemAgentEntry
object AutoBackupModuleDataHook : CommonSwitchFunctionHook(
    hookKey = "timas_auto_backup_module_data",
    targetProc = SyncUtils.PROC_MAIN
) {

    private val lastBackupDate = ConfigData<String>("timas_auto_backup_last_date")

    private const val KEEP_COUNT = 7
    private const val DIR_NAME = "qa_auto_backup"

    override val name: String = "每天自动备份模块数据"

    override val description: String =
        "每次启动主进程时检查，每天最多备份一次到 外部私有目录/qa_auto_backup/，保留最近 $KEEP_COUNT 份"

    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Auxiliary.EXPERIMENTAL_CATEGORY

    override fun initOnce(): Boolean {
        return try {
            Thread {
                try {
                    runBackupIfNeeded()
                } catch (t: Throwable) {
                    traceError(t)
                }
            }.apply {
                name = "QA-AutoBackup"
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }.start()
            true
        } catch (t: Throwable) {
            traceError(t)
            false
        }
    }

    private fun runBackupIfNeeded() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (today == lastBackupDate.getOrDefault("")) {
            return
        }

        val app = HostInfo.getApplication() ?: return
        val outDir = File(app.getExternalFilesDir(null) ?: app.filesDir, DIR_NAME)
        if (!outDir.exists() && !outDir.mkdirs()) {
            return
        }
        // 别让系统相册索引到备份文件
        runCatching { File(outDir, ".nomedia").createNewFile() }

        var session: BackupConfigSession? = null
        try {
            session = BackupConfigSession(app)
            val configs = session.listMmkvConfig().filter { it != "global_cache" }
            if (configs.isEmpty()) {
                return
            }
            configs.forEach { session.backupMmkvConfigToWorkDir(it) }
            val zip = session.createBackupZipFile()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val target = File(outDir, "QAuxiliary_$stamp.zip")
            zip.copyTo(target, overwrite = true)
            runCatching { zip.delete() }

            pruneOldBackups(outDir)
            lastBackupDate.value = today
        } finally {
            runCatching { session?.close() }
        }
    }

    private fun pruneOldBackups(dir: File) {
        val zips = dir.listFiles { f ->
            f.isFile && f.name.endsWith(".zip", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() } ?: return
        zips.drop(KEEP_COUNT).forEach { runCatching { it.delete() } }
    }
}
