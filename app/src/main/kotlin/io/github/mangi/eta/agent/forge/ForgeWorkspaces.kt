package io.github.mangi.eta.agent.forge

import io.github.mangi.eta.agent.terminal.PrefsWriteLock
import io.github.mangi.eta.agent.terminal.SharedFolderMount
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.config.Prefs
import org.json.JSONObject
import java.io.File

/**
 * Forge 的工作区（照 DSH 的模型）：一个工作区就是一个命名的文件夹——Android 侧的真实目录，
 * 经共享文件夹机制挂到 Linux 环境的 /workspace/mounts/<名>。会话归属于创建时选中的工作区，
 * opencode server 以该目录为 cwd 启动、在里面干活，产出的文件就落在那个 Android 文件夹里，
 * 用户在文件管理器里直接能拿到，不再困在环境私有目录。
 *
 * 工作区本体就是一条共享文件夹挂载（[SharedFolderMount]），不另建一套存储；
 * 这里只管：默认工作区的自建、当前选择的持久化、增删改名。
 */
internal data class ForgeWorkspace(
    /** 挂载名：`/workspace/mounts/<name>` 的路径段，也是侧边栏分组、会话归属的键。 */
    val name: String,
    val sourcePath: String,
    /**
     * 界面显示名（3.4.43 B3）：保留用户原文/源目录原名，**可以带空格**。
     * 以前只有 [name] 一个字段，它同时当挂载名、路径段和所有界面显示名，而挂载名必须剔除
     * 空白，于是 `/storage/emulated/0/AI_Workspace/ETA Sol` 被显示成 `ETASolWorkspace`。
     * 现在所有展示处一律用 displayName，挂载与 linuxPath 继续用清洗后的 [name]。
     */
    val displayName: String = name,
) {
    /** 环境内的绝对路径（server 的 cwd、附件 file part 的 url 都用它）。 */
    val linuxPath: String
        get() = "${SharedFolderMounts.LINUX_MOUNTS_ROOT}/$name"
}

internal object ForgeWorkspaces {
    const val DEFAULT_NAME = "EtaForge"
    const val DEFAULT_SOURCE = "/sdcard/Download/EtaForge"

    /** 没有任何工作区可用时的回落 cwd（环境私有目录，界面会明示文件拿不出来）。 */
    const val FALLBACK_CWD = "/workspace"

    private const val PREFS_KEY_CURRENT = "forge_current_workspace"

    fun all(): List<ForgeWorkspace> =
        SharedFolderMounts.current().map { mount ->
            ForgeWorkspace(
                name = mount.name,
                sourcePath = mount.sourcePath,
                displayName = SharedFolderMounts.displayNameOf(mount),
            )
        }

    private const val PREFS_KEY_DEFAULT_CREATED = "forge_default_workspace_created"

    /** 改名别名表 {旧挂载名: 新挂载名}：会话的 directory 不迁移，靠这张表把旧会话归到新工作区。 */
    private const val PREFS_KEY_ALIASES = "forge_workspace_aliases"

    /** 别名链最多解析这么多跳：防环，也防历史数据把链拉得过长。 */
    private const val MAX_ALIAS_HOPS = 16

    private fun defaultCreated(): Boolean = runCatching {
        Prefs.localAgentPreferences()?.getBoolean(PREFS_KEY_DEFAULT_CREATED, false)
    }.getOrNull() == true

    private fun markDefaultCreated() {
        runCatching {
            Prefs.localAgentPreferences()?.edit()
                ?.putBoolean(PREFS_KEY_DEFAULT_CREATED, true)?.apply()
        }
    }

    /**
     * 目录存在就直接过；缺失时补建并复核结果——建不出来（没存储权限等）返回 false。
     * mkdirs 的返回值不可信（父目录已存在时也会返回 false），一律以建完之后的 isDirectory 为准。
     */
    private fun ensureSourceDir(path: String): Boolean {
        val dir = File(path)
        if (dir.isDirectory) return true
        runCatching { dir.mkdirs() }
        return dir.isDirectory
    }

    /** 读别名表；坏 JSON、非字符串值、自指条目一律忽略，读不到当空表。 */
    private fun loadAliases(): Map<String, String> = runCatching {
        val raw = Prefs.localAgentPreferences()?.getString(PREFS_KEY_ALIASES, null).orEmpty()
        if (raw.isBlank()) {
            emptyMap()
        } else {
            val json = JSONObject(raw)
            val aliases = mutableMapOf<String, String>()
            json.keys().forEach { key ->
                val from = key.trim()
                val to = json.optString(key).trim()
                if (from.isNotEmpty() && to.isNotEmpty() && from != to) aliases[from] = to
            }
            aliases
        }
    }.getOrElse { emptyMap<String, String>() }

    private fun saveAliases(aliases: Map<String, String>): Boolean = runCatching {
        val json = JSONObject()
        aliases.forEach { (from, to) -> json.put(from, to) }
        Prefs.localAgentPreferences()?.edit()
            ?.putString(PREFS_KEY_ALIASES, json.toString())?.apply()
        true
    }.getOrDefault(false)

    /**
     * 记录一次改名的别名：除 [oldName] → [newName] 外，还把原本指向 [oldName] 的别名统一改指 [newName]，
     * 这样连续改名 A→B→C 不会攒出长链；[newName] 自身若曾是某条别名的起点也一并丢掉（那个旧名字不复存在）。
     */
    private fun recordAlias(oldName: String, newName: String) {
        if (oldName == newName) return
        val aliases = loadAliases().toMutableMap()
        aliases.entries.filter { it.value == oldName }.forEach { it.setValue(newName) }
        // 反着改回来时（B→A 之后又 A→B）会留下自指条目，读的时候虽被过滤，这里先清掉免得越攒越脏。
        aliases.entries.removeAll { it.key == it.value }
        aliases.remove(newName)
        aliases[oldName] = newName
        saveAliases(aliases)
    }

    /** 清掉值指向 [name] 的别名：工作区被删掉后不该再被别名指回来。 */
    private fun clearAliasesPointingTo(name: String) {
        val aliases = loadAliases()
        val pruned = aliases.filterValues { it != name }
        if (pruned.size != aliases.size) saveAliases(pruned)
    }

    /** 当前别名表快照（旧挂载名 → 新挂载名），只读。 */
    fun aliases(): Map<String, String> = loadAliases()

    /**
     * 沿别名链把 [name] 解析到最终名字：最多 [MAX_ALIAS_HOPS] 跳，遇到环或断链就停在当前名字。
     */
    fun canonicalName(name: String): String {
        if (name.isEmpty()) return name
        val aliases = loadAliases()
        if (aliases.isEmpty() || name !in aliases) return name
        var current = name
        val visited = mutableSetOf(current)
        var hops = 0
        while (hops < MAX_ALIAS_HOPS) {
            val next = aliases[current] ?: break
            if (!visited.add(next)) break
            current = next
            hops++
        }
        return current
    }

    /**
     * 把一个 opencode 会话的 directory（形如 /workspace/mounts/<名>）解析到当前工作区的路径：
     * 工作区改过名时旧会话仍记着旧挂载名，经别名表归到新名下，侧边栏才不会把它丢进「其它会话」。
     * 命中挂载路径一级子目录时统一归一化成 `前缀 + 规范化名字`（去掉尾斜杠与首尾空白）；
     * 不是挂载路径、或层数超过一级时原样返回输入。
     */
    fun canonicalDirectory(directory: String): String {
        if (directory.isBlank()) return directory
        val prefix = "${SharedFolderMounts.LINUX_MOUNTS_ROOT}/"
        val trimmed = directory.trim()
        if (!trimmed.startsWith(prefix)) return directory
        val name = trimmed.substring(prefix.length).trimEnd('/')
        if (name.isEmpty() || '/' in name) return directory
        val canonical = canonicalName(name)
        // 即使没有别名也要回填归一化后的形态：opencode 可能把 directory 记成
        // `/workspace/mounts/<名>/`（尾斜杠）或带首尾空白，而侧边栏分组是拿它和
        // `ForgeWorkspace.linuxPath`（无尾斜杠）做字符串相等的，不归一化就会掉进「其它会话」。
        if (canonical == name) return prefix + canonical
        if (SharedFolderMounts.current().none { it.name == canonical }) return directory
        return prefix + canonical
    }

    /**
     * 确保默认工作区存在：已有同名挂载直接用；已有指向同一目录的挂载就用那条（不重复建）；
     * 都没有就建目录 + 追加挂载。动不了既有挂载。返回默认工作区，实在建不出来返回 null。
     * 只在从没建过时自动建：用户主动移除过默认工作区就别再复活它，不然永远删不掉。
     *
     * 目录必须先真的建出来（[ensureSourceDir]）：没有存储权限时 mkdirs 失败，此时保存挂载只会留下
     * 一个 `cd` 不进去的工作区，所以不保存挂载、也不写 created-once 标记，让下次进 Forge 再试。
     * 已存在的挂载同样要复核目录：缺失就补建，补不出来也不能当成可用的默认工作区（仍不写标记）。
     */
    fun ensureDefault(): ForgeWorkspace? = synchronized(PrefsWriteLock.lock) {
        fun workspaceOf(mount: SharedFolderMount) = ForgeWorkspace(
            name = mount.name,
            sourcePath = mount.sourcePath,
            displayName = SharedFolderMounts.displayNameOf(mount),
        )
        val mounts = SharedFolderMounts.current()
        mounts.firstOrNull { it.name == DEFAULT_NAME }
            ?.let { mount ->
                if (!ensureSourceDir(mount.sourcePath)) return@synchronized null
                markDefaultCreated()
                return@synchronized workspaceOf(mount)
            }
        mounts.firstOrNull { it.sourcePath == DEFAULT_SOURCE }
            ?.let { mount ->
                if (!ensureSourceDir(mount.sourcePath)) return@synchronized null
                markDefaultCreated()
                return@synchronized workspaceOf(mount)
            }
        if (defaultCreated()) return@synchronized null
        if (!ensureSourceDir(DEFAULT_SOURCE)) return@synchronized null
        if (SharedFolderMounts.validateName(DEFAULT_NAME, mounts) != null) return@synchronized null
        if (SharedFolderMounts.validateSource(DEFAULT_SOURCE, mounts) != null) return@synchronized null
        val fresh = SharedFolderMount(
            name = DEFAULT_NAME,
            sourcePath = DEFAULT_SOURCE,
            displayName = SharedFolderMounts.sourceDisplayName(DEFAULT_SOURCE),
        )
        if (!SharedFolderMounts.save(mounts + fresh)) return@synchronized null
        markDefaultCreated()
        workspaceOf(fresh)
    }

    /** 当前选中的工作区：持久化的选择还存在就用它，否则默认工作区，再否则第一条；一条都没有返回 null。 */
    fun currentSelection(): ForgeWorkspace? {
        val workspaces = all()
        if (workspaces.isEmpty()) return null
        val savedName = runCatching {
            Prefs.localAgentPreferences()?.getString(PREFS_KEY_CURRENT, null)
        }.getOrNull()
        return workspaces.firstOrNull { it.name == savedName }
            ?: workspaces.firstOrNull { it.name == DEFAULT_NAME }
            ?: workspaces.firstOrNull { it.sourcePath == DEFAULT_SOURCE }
            ?: workspaces.first()
    }

    fun select(name: String) {
        // 与增删改名共用一把锁：选择与挂载表改动交错时，别写回一个刚被删掉的名字。
        synchronized(PrefsWriteLock.lock) {
            runCatching {
                Prefs.localAgentPreferences()?.edit()?.putString(PREFS_KEY_CURRENT, name)?.apply()
            }
        }
    }

    /**
     * 工作区被删掉后清掉「当前选择」指针——前提是它正指向 [name]。
     * 留着不清的话：同名工作区重建时 prefs 里的旧名字会重新命中，旧选择凭空复活。
     * 清空而不是改写成「剩下的首选」：currentSelection() 自带回落顺序（默认名 → 默认目录 → 第一条），
     * 这里再写死一个名字，那个名字后面被删又会变成新的悬空指针。
     */
    private fun clearSelectionPointingTo(name: String) {
        runCatching {
            val prefs = Prefs.localAgentPreferences() ?: return
            if (prefs.getString(PREFS_KEY_CURRENT, null) != name) return
            prefs.edit().remove(PREFS_KEY_CURRENT).apply()
        }
    }

    /** 新建工作区：从 Android 目录路径推导挂载名（重名自动加后缀），建目录并保存挂载。 */
    fun add(sourcePathRaw: String): Result<ForgeWorkspace> = synchronized(PrefsWriteLock.lock) {
        val source = SharedFolderMounts.normalizeSourcePath(sourcePathRaw)
            ?: return@synchronized Result.failure(ForgeWorkspaceException(WorkspaceFailure.SOURCE_INVALID))
        val mounts = SharedFolderMounts.current()
        SharedFolderMounts.validateSource(source, mounts)?.let { error ->
            val reason = if (error == SharedFolderMounts.SourceError.DUPLICATE) {
                WorkspaceFailure.SOURCE_DUPLICATE
            } else {
                WorkspaceFailure.SOURCE_FORBIDDEN
            }
            return@synchronized Result.failure(ForgeWorkspaceException(reason))
        }
        // 读侧 decode() 超限时会静默截断，写侧必须先把话说清楚：
        // 否则第 17 个工作区「添加成功」，下次 all() 里凭空消失。
        if (mounts.size >= SharedFolderMounts.MAX_MOUNTS) {
            return@synchronized Result.failure(ForgeWorkspaceException(WorkspaceFailure.TOO_MANY))
        }
        // 目录建不出来就别保存：否则「添加成功」的挂载在环境里 cd 不进去。
        if (!ensureSourceDir(source)) {
            return@synchronized Result.failure(ForgeWorkspaceException(WorkspaceFailure.DIR_UNAVAILABLE))
        }
        var name = SharedFolderMounts.defaultName(source)
        var suffix = 2
        while (SharedFolderMounts.validateName(name, mounts) != null) {
            name = "${SharedFolderMounts.defaultName(source).take(44)}-$suffix"
            suffix++
            if (suffix > 32) {
                return@synchronized Result.failure(ForgeWorkspaceException(WorkspaceFailure.NAME_DUPLICATE))
            }
        }
        // B3：显示名保留源目录原名（可含空格），与清洗后的挂载名分开存。
        val display = uniqueDisplayName(SharedFolderMounts.sourceDisplayName(source), mounts)
        val mount = SharedFolderMount(name = name, sourcePath = source, displayName = display)
        val saved = SharedFolderMounts.save(mounts + mount)
        val workspace = ForgeWorkspace(name = name, sourcePath = source, displayName = display)
        return@synchronized if (saved) {
            Result.success(workspace)
        } else {
            Result.failure(ForgeWorkspaceException(WorkspaceFailure.SAVE_FAILED))
        }
    }

    /**
     * 显示名去重：不同父目录下的同名目录会撞显示名，追加「 2」「 3」…（显示名允许空格）。
     * 挂载名另有自己的 `-2..-32` 去重，两套互不影响。
     */
    private fun uniqueDisplayName(base: String, existing: List<SharedFolderMount>): String {
        if (SharedFolderMounts.validateDisplayName(base, existing) == null) return base
        var suffix = 2
        while (suffix <= 32) {
            val candidate = "${base.take(SharedFolderMounts.DISPLAY_NAME_MAX - 3)} $suffix"
            if (SharedFolderMounts.validateDisplayName(candidate, existing) == null) return candidate
            suffix++
        }
        return base
    }

    /**
     * 改名（3.4.43 B3）：改的是**显示名**（`displayName`），保留用户原文与空格。
     *
     * 挂载名与 linuxPath **保持不变**——它们是会话 directory、附件落盘路径与 server cwd 的依据，
     * 跟着改会让所有历史会话按新路径找不到、还要靠别名表兜底。所以：
     * · 挂载名如何派生：新增工作区时由源目录名清洗得出（`SharedFolderMounts.defaultName`），
     *   之后不再随显示名变化；
     * · 显示名只影响界面，改它不需要重启 server，也不需要记别名。
     */
    fun rename(oldName: String, newNameRaw: String): Result<ForgeWorkspace> = synchronized(PrefsWriteLock.lock) {
        val mounts = SharedFolderMounts.current()
        val index = mounts.indexOfFirst { it.name == oldName }
        if (index < 0) return@synchronized Result.failure(ForgeWorkspaceException(WorkspaceFailure.NOT_FOUND))
        val target = mounts[index]
        val newDisplay = newNameRaw.trim()
        SharedFolderMounts.validateDisplayName(newDisplay, mounts, selfName = oldName)?.let { error ->
            val reason = if (error == SharedFolderMounts.NameError.DUPLICATE) {
                WorkspaceFailure.NAME_DUPLICATE
            } else {
                WorkspaceFailure.NAME_INVALID
            }
            return@synchronized Result.failure(ForgeWorkspaceException(reason))
        }
        val updated = mounts.toMutableList()
        // N14：按原下标替换。以前用 `others + 新条`，改名项会被挪到挂载表末尾，
        // 侧边栏按表顺序渲染，于是「改个名就沉到最底下」。
        updated[index] = target.copy(displayName = newDisplay)
        if (!SharedFolderMounts.save(updated)) {
            return@synchronized Result.failure(ForgeWorkspaceException(WorkspaceFailure.SAVE_FAILED))
        }
        Result.success(ForgeWorkspace(name = target.name, sourcePath = target.sourcePath, displayName = newDisplay))
    }

    /** 移除工作区：只删挂载记录，文件夹与里面的文件都不动；指向它的别名一并清掉，免得删了又被指回来。 */
    fun remove(name: String): Boolean = synchronized(PrefsWriteLock.lock) {
        val mounts = SharedFolderMounts.current()
        if (mounts.none { it.name == name }) return@synchronized false
        if (!SharedFolderMounts.save(mounts.filter { it.name != name })) return@synchronized false
        clearAliasesPointingTo(name)
        // 删掉的就是当前工作区时把这个指针也清掉：prefs 里留着旧名字，同名重建时旧选择会自己复活。
        clearSelectionPointingTo(name)
        true
    }

    /**
     * 当前全部挂载的指纹：server 启动时焊入的是当时的挂载集合，指纹变了说明挂载改过、
     * 在跑的 server 看不到新挂载。
     *
     * 3.4.43（N16）：以前是拼串后取 32 位 `hashCode()`——不同挂载集合撞同一个值的概率不可忽略，
     * 撞上就「结构变了却不重启 server」。现在直接返回拼好的多行串（prefs 里就是字符串，
     * 长度不是问题），零碰撞。只含 name/source：显示名变化不影响 server 行为，不该触发重启。
     */
    fun mountsFingerprint(): String =
        SharedFolderMounts.current()
            .joinToString(separator = "\n") { "${it.name}\u0000${it.sourcePath}" }

    /** 工作区操作失败的具体原因（3.4.43 N18）：界面据此给人话提示，而不是一律「无法添加」。 */
    enum class WorkspaceFailure {
        /** 路径本身不合法。 */
        SOURCE_INVALID,

        /** 路径命中禁区（系统树、存储根等）。 */
        SOURCE_FORBIDDEN,

        /** 这个目录已经是某个工作区了。 */
        SOURCE_DUPLICATE,

        /** 名字里有非法字符或为空。 */
        NAME_INVALID,

        /** 名字和其它工作区重名。 */
        NAME_DUPLICATE,

        /** 已达工作区数量上限。 */
        TOO_MANY,

        /** 目录建不出来（权限等）。 */
        DIR_UNAVAILABLE,

        /** 落盘失败。 */
        SAVE_FAILED,

        /** 目标工作区不存在（可能已被删）。 */
        NOT_FOUND,
    }

    /** 带原因的失败：界面按 [reason] 选文案。 */
    class ForgeWorkspaceException(val reason: WorkspaceFailure) : Exception(reason.name)
}
