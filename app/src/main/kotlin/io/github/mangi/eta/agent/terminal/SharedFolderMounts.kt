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
/**
 * 一条共享文件夹挂载。
 *
 * 3.4.43（B3）：[name] 是**挂载名**——它会拼进 `/workspace/mounts/<name>` 与 bind mount 参数，
 * 只能是 [SharedFolderMounts.NAME_PATTERN] 允许的字符（无空白），所以源目录叫 `ETA Sol`
 * 时它只能清洗成 `ETASolWorkspace` 这类形态。
 * [displayName] 是**显示名**——保留用户原文（可以带空格），只用于界面展示；为 null 时
 * 由 [SharedFolderMounts.displayNameOf] 从源目录 basename 推导。
 * 旧数据没有 displayName 字段，解码后为 null，展示层会自动回落到源目录原名，
 * 所以历史挂载也能立刻显示成 `ETA Sol` 而不是 `ETASolWorkspace`。
 */
internal data class SharedFolderMount(
    val name: String,
    val sourcePath: String,
    val displayName: String? = null,
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

    /**
     * 挂载名字符集：Unicode 字母、数字与 `.` `_` `-`，长度 1..48。
     *
     * 3.4.42 规格变更（SG-08）：从「纯 ASCII」放宽到「Unicode 字母」，让中文目录能保留原名
     * （见 [defaultName]）。空白、`/`、`\`、`:`、引号等路径/shell 元字符仍一律拒绝——名字会被
     * 拼进 `/workspace/mounts/<名>` 与 bind mount 参数，含元字符就会破坏目录层级或参数解析。
     */
    private val NAME_PATTERN = Regex("[\\p{L}\\p{N}._-]{1,48}")

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

    /** 显示名长度上限：与挂载名一致，够放下整个目录原名。 */
    const val DISPLAY_NAME_MAX = 48

    /**
     * 从源目录 basename 推导「显示名」：**保留原文**（含空格、汉字、标点），只做必要的安全清洗。
     *
     * 3.4.43（B3）：以前显示名就是挂载名，而挂载名必须剔除空白，于是
     * `/storage/emulated/0/AI_Workspace/ETA Sol` 在界面上显示成 `ETASolWorkspace`。
     * 显示名不参与任何路径拼接，所以空白可以留；只剔掉会破坏界面/路径解析的字符
     * （`/`、`\`、控制字符），并把长度截到 [DISPLAY_NAME_MAX]。
     */
    fun sourceDisplayName(sourcePath: String): String {
        val base = sourcePath.trimEnd('/').substringAfterLast('/').trim()
        val cleaned = base.filter { char -> char != '/' && char != '\\' && !char.isISOControl() }
            .trim()
            .take(DISPLAY_NAME_MAX)
        return cleaned.ifBlank { defaultName(sourcePath) }
    }

    /** 一条挂载的展示名：有 displayName 用它，否则回落到源目录原名（旧数据/未设过都走这里）。 */
    fun displayNameOf(mount: SharedFolderMount): String {
        val explicit = mount.displayName?.trim().orEmpty()
        return explicit.ifBlank { sourceDisplayName(mount.sourcePath) }
    }

    /**
     * 用户输入的新显示名校验：允许空白与任意 Unicode（中文照旧保留），
     * 只拒绝空、超长、含路径分隔符/控制字符、`.`/`..`，以及在**其它**挂载上重复。
     * 挂载名与它无关，所以不套 [NAME_PATTERN]。
     */
    fun validateDisplayName(
        displayName: String,
        existing: List<SharedFolderMount>,
        selfName: String? = null,
    ): NameError? {
        val trimmed = displayName.trim()
        if (trimmed.isEmpty() || trimmed.length > DISPLAY_NAME_MAX) return NameError.INVALID
        if (trimmed == "." || trimmed == "..") return NameError.INVALID
        if (trimmed.any { it == '/' || it == '\\' || it.isISOControl() }) return NameError.INVALID
        val clash = existing.any { other ->
            other.name != selfName && displayNameOf(other) == trimmed
        }
        if (clash) return NameError.DUPLICATE
        return null
    }

    /**
     * 从源目录 basename 推导默认挂载名。
     *
     * 3.4.42 规格变更（SG-08）：**保留 Unicode 字母（含汉字）**，不再把非 ASCII 字符整段剔掉。
     * 旧规格下 `/sdcard/下载` 得到 `share`、`/sdcard/文档` 也是 `share`，第二个再被去重成
     * `share-2`，用户完全无法从名字分辨哪个目录是哪个；而挂载名同时是 `/workspace/mounts/<名>`
     * 的路径段，也是 Forge 工作区显示名，可读性优先。
     *
     * 仍然剔掉：空白、路径分隔符、`:` 与其它 shell/JSON 元字符（这些会破坏 bind mount 参数或
     * 目录层级）。名字最终会经 [ProotCommandBuilder] 的 `shellQuote` 与
     * [TerminalExecutionContext] 的 `JSONObject.quote` 处理，非 ASCII 本身是安全的。
     *
     * 只有当 basename 里连一个字母/数字都不剩（例如纯标点、纯 emoji）时才回落到 `share`，
     * 此时同名冲突仍由挂载表的 `-2..-32` 去重兜住。
     */
    fun defaultName(sourcePath: String): String {
        val base = sourcePath.trimEnd('/').substringAfterLast('/').trim()
        return base.filter { char ->
            char.isLetterOrDigit() || char == '.' || char == '_' || char == '-'
        }.take(48).ifBlank { "share" }
    }

    fun encode(mounts: List<SharedFolderMount>): String {
        val array = JSONArray()
        mounts.forEach { mount ->
            val item = JSONObject()
                .put("name", mount.name)
                .put("source", mount.sourcePath)
            // B3：只在显式设过、且确实不等于挂载名时写 display，旧格式保持原样（不留冗余字段）。
            val display = mount.displayName?.trim().orEmpty()
            if (display.isNotEmpty() && display != mount.name) item.put("display", display)
            array.put(item)
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
            // 显示名宽松清洗：坏值（含分隔符/控制字符/超长）当没设过，由 displayNameOf 回落到源目录名。
            val display = item.optString("display").trim()
                .filter { it != '/' && it != '\\' && !it.isISOControl() }
                .trim()
                .take(DISPLAY_NAME_MAX)
                .takeIf { it.isNotEmpty() && it != "." && it != ".." && it != name }
            mounts += SharedFolderMount(name = name, sourcePath = source, displayName = display)
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
