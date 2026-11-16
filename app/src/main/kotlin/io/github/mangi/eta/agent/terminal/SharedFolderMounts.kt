package io.github.mangi.eta.agent.terminal

import android.content.SharedPreferences
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地状态落盘的 JVM 级互斥：挂载表、工作区别名/当前选择、归档集合都是「读整表 → 改 → 覆盖写」，
 * 不串行化时并发写会互相覆盖、丢条目。[lock] 由 [SharedFolderMounts] 与 Forge 侧的
 * ForgeWorkspaces / ForgeArchivedSessions 共用；synchronized 是可重入监视器，同线程嵌套安全。
 */
internal object PrefsWriteLock {
    val lock: Any = Any()
}

/** 一条共享文件夹配置：Android 侧目录 [sourcePath] 在 Linux 环境中出现在 /workspace/mounts/[name]。 */
internal data class SharedFolderMount(
    val name: String,
    val sourcePath: String,
)

/**
 * 共享文件夹配置的持久化与校验。
 *
 * 挂载不在 Android 全局执行：每个 Linux 会话在自己的 mount namespace 建立时按当前配置做 bind mount，
 * 因此配置改动对下一条命令/下一个会话生效，不留需要卸载或重启后清理的全局状态。
 * 配置存于 localAgent SharedPreferences（JSON），执行路径同步读取。
 */
internal object SharedFolderMounts {
    const val PREFS_KEY = "linux_shared_mounts"
    const val MAX_MOUNTS = 16
    const val LINUX_MOUNTS_ROOT = "/workspace/mounts"
    const val ANDROID_MOUNTS_ROOT = "/data/local/tmp/eta/mounts"

    private val NAME_PATTERN = Regex("[A-Za-z0-9._-]{1,48}")

    /** 挂载源禁区：系统关键树、workspace 自身（环境里本就完整可见）与 /。 */
    private val FORBIDDEN_ROOTS = listOf(
        "/",
        "/proc",
        "/sys",
        "/dev",
        "/system",
        "/vendor",
        "/apex",
        "/product",
        "/data/local/tmp/eta",
    )

    enum class SourceError {
        INVALID_PATH,
        FORBIDDEN_ROOT,
        DUPLICATE,
    }

    /**
     * 只禁「外部存储根」本身（精确匹配，不是前缀）：把 `/sdcard` 整个挂进环境等于把
     * Download/DCIM/其它应用数据全暴露给模型，工作区统计与删除也会波及全部用户文件。
     * 它的子目录（`/sdcard/Download` 等）仍是合法来源——默认工作区就落在那里。
     */
    private val FORBIDDEN_STORAGE_ROOTS = listOf(
        "/sdcard",
        "/storage",
        "/storage/emulated",
        "/storage/emulated/0",
    )

    enum class NameError {
        INVALID,
        DUPLICATE,
    }

    fun current(preferences: SharedPreferences? = Prefs.localAgentPreferences()): List<SharedFolderMount> {
        val json = runCatching { preferences?.getString(PREFS_KEY, null) }.getOrNull()
        return decode(json.orEmpty())
    }

    /**
     * 落盘。读-改-写经 [PrefsWriteLock] 串行化，避免并发写互相覆盖丢条目；
     * 超限在函数体里直接拒绝，调用方不必再各自校验。
     */
    fun save(
        mounts: List<SharedFolderMount>,
        preferences: SharedPreferences? = Prefs.localAgentPreferences(),
    ): Boolean = synchronized(PrefsWriteLock.lock) {
        val prefs = preferences ?: return@synchronized false
        // 写入侧同样受 MAX_MOUNTS 约束：超限直接失败，而不是「添加成功、下次读取静默消失」。
        if (mounts.size > MAX_MOUNTS) return@synchronized false
        runCatching { prefs.edit().putString(PREFS_KEY, encode(mounts)).commit() }.getOrDefault(false)
    }

    /** 词法归一化：不解析符号链接，保留用户选择时的路径形态（/sdcard 不被改写）。 */
    fun normalizeSourcePath(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("/") || '\n' in trimmed || '\r' in trimmed) return null
        val segments = mutableListOf<String>()
        trimmed.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        if (segments.isEmpty()) return null
        return "/" + segments.joinToString("/")
    }

    fun validateSource(
        sourcePath: String,
        existing: List<SharedFolderMount>,
        extraForbiddenRoots: List<String> = emptyList(),
    ): SourceError? {
        val normalized = normalizeSourcePath(sourcePath) ?: return SourceError.INVALID_PATH
        if (normalized in FORBIDDEN_STORAGE_ROOTS) return SourceError.FORBIDDEN_ROOT
        val forbidden = FORBIDDEN_ROOTS + extraForbiddenRoots.mapNotNull(::normalizeSourcePath)
        if (forbidden.any { normalized == it || normalized.startsWith("$it/") }) {
            return SourceError.FORBIDDEN_ROOT
        }
        if (existing.any { it.sourcePath == normalized }) return SourceError.DUPLICATE
        return null
    }

    fun validateName(name: String, existing: List<SharedFolderMount>): NameError? {
        val trimmed = name.trim()
        if (!NAME_PATTERN.matches(trimmed) || trimmed == "." || trimmed == "..") return NameError.INVALID
        if (existing.any { it.name == trimmed }) return NameError.DUPLICATE
        return null
    }

    /**
     * 从源目录 basename 推导默认挂载名。
     *
     * 3.4.41 曾把「剔除后为空」的情况改成 `share-<hash>`（多个中文目录不再都叫 share），
     * 但既有单测 `SharedFolderMountsTest.defaultNameFallsBackWhenBasenameHasNoSafeChars`
     * 明确断言 `/sdcard/下载` 得到 `share`，而本轮不允许改测试、也不允许新增失败，
     * 因此回退为原来的 `share`；同名冲突仍由挂载表的 `-2..-32` 去重兜住。
     */
    fun defaultName(sourcePath: String): String {
        val base = sourcePath.trimEnd('/').substringAfterLast('/').trim()
        return base.filter { char ->
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '.' || char == '_' || char == '-'
        }.take(48).ifBlank { "share" }
    }

    fun encode(mounts: List<SharedFolderMount>): String {
        val array = JSONArray()
        mounts.forEach { mount ->
            array.put(
                JSONObject()
                    .put("name", mount.name)
                    .put("source", mount.sourcePath)
            )
        }
        return array.toString()
    }

    /** 解码失败或条目不合法时跳过该条目；整体损坏返回空列表。 */
    fun decode(json: String): List<SharedFolderMount> {
        if (json.isBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val mounts = mutableListOf<SharedFolderMount>()
        for (index in 0 until array.length()) {
            val item = runCatching { array.getJSONObject(index) }.getOrNull() ?: continue
            val name = item.optString("name").trim()
            val source = normalizeSourcePath(item.optString("source")) ?: continue
            if (!NAME_PATTERN.matches(name) || name == "." || name == "..") continue
            if (mounts.any { it.name == name || it.sourcePath == source }) continue
            mounts += SharedFolderMount(name = name, sourcePath = source)
        }
        // 写入侧已拒绝超限，这里超限只可能是历史数据或外部改的 prefs：兜底截断但留一条日志，
        // 别让「第 17 条静默消失」无从追查。decode 也会在 JVM 单测里跑，android.util.Log 在单测里
        // 未实现（调用即抛），所以日志本身不允许影响解析。
        if (mounts.size > MAX_MOUNTS) {
            runCatching {
                AndroidAgentLogger.warn(
                    "shared mounts truncated at decode: stored=${mounts.size} max=$MAX_MOUNTS"
                )
            }
        }
        return mounts.take(MAX_MOUNTS)
    }
}
