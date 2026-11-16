package io.github.mangi.eta.agent.forge

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
    val name: String,
    val sourcePath: String,
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
        SharedFolderMounts.current().map { ForgeWorkspace(it.name, it.sourcePath) }

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
     * 不是挂载路径、层数超过一级、或解析结果不是当前挂载之一时，原样返回输入。
     */
    fun canonicalDirectory(directory: String): String {
        if (directory.isBlank()) return directory
        val prefix = "${SharedFolderMounts.LINUX_MOUNTS_ROOT}/"
        val trimmed = directory.trim()
        if (!trimmed.startsWith(prefix)) return directory
        val name = trimmed.substring(prefix.length).trimEnd('/')
        if (name.isEmpty() || '/' in name) return directory
        val canonical = canonicalName(name)
        if (canonical == name) return directory
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
    fun ensureDefault(): ForgeWorkspace? {
        val mounts = SharedFolderMounts.current()
        mounts.firstOrNull { it.name == DEFAULT_NAME }
            ?.let { mount ->
                if (!ensureSourceDir(mount.sourcePath)) return null
                markDefaultCreated()
                return ForgeWorkspace(mount.name, mount.sourcePath)
            }
        mounts.firstOrNull { it.sourcePath == DEFAULT_SOURCE }
            ?.let { mount ->
                if (!ensureSourceDir(mount.sourcePath)) return null
                markDefaultCreated()
                return ForgeWorkspace(mount.name, mount.sourcePath)
            }
        if (defaultCreated()) return null
        if (!ensureSourceDir(DEFAULT_SOURCE)) return null
        if (SharedFolderMounts.validateName(DEFAULT_NAME, mounts) != null) return null
        if (SharedFolderMounts.validateSource(DEFAULT_SOURCE, mounts) != null) return null
        val updated = mounts + SharedFolderMount(name = DEFAULT_NAME, sourcePath = DEFAULT_SOURCE)
        if (!SharedFolderMounts.save(updated)) return null
        markDefaultCreated()
        return ForgeWorkspace(DEFAULT_NAME, DEFAULT_SOURCE)
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
        runCatching {
            Prefs.localAgentPreferences()?.edit()?.putString(PREFS_KEY_CURRENT, name)?.apply()
        }
    }

    /** 新建工作区：从 Android 目录路径推导挂载名（重名自动加后缀），建目录并保存挂载。 */
    fun add(sourcePathRaw: String): Result<ForgeWorkspace> {
        val source = SharedFolderMounts.normalizeSourcePath(sourcePathRaw)
            ?: return Result.failure(IllegalArgumentException("invalid source path"))
        val mounts = SharedFolderMounts.current()
        SharedFolderMounts.validateSource(source, mounts)?.let {
            return Result.failure(IllegalArgumentException("source rejected: $it"))
        }
        // 目录建不出来就别保存：否则「添加成功」的挂载在环境里 cd 不进去。
        if (!ensureSourceDir(source)) {
            return Result.failure(IllegalStateException("cannot create directory: $source"))
        }
        var name = SharedFolderMounts.defaultName(source)
        var suffix = 2
        while (SharedFolderMounts.validateName(name, mounts) != null) {
            name = "${SharedFolderMounts.defaultName(source).take(44)}-$suffix"
            suffix++
            if (suffix > 32) return Result.failure(IllegalArgumentException("cannot derive unique name"))
        }
        val workspace = ForgeWorkspace(name = name, sourcePath = source)
        val saved = SharedFolderMounts.save(mounts + SharedFolderMount(name = name, sourcePath = source))
        return if (saved) Result.success(workspace) else Result.failure(IllegalStateException("save failed"))
    }

    /**
     * 改名：只改挂载名（Linux 路径随之变），目录内容不动；改的是当前工作区时选择跟着走。
     * 会话的 directory 不迁移（那是 server 侧数据），改为记一条别名，由 [canonicalDirectory] 归位。
     */
    fun rename(oldName: String, newNameRaw: String): Result<ForgeWorkspace> {
        val mounts = SharedFolderMounts.current()
        val target = mounts.firstOrNull { it.name == oldName }
            ?: return Result.failure(IllegalArgumentException("workspace not found"))
        val wasCurrent = currentSelection()?.name == oldName
        val newName = newNameRaw.trim()
        val others = mounts.filter { it.name != oldName }
        SharedFolderMounts.validateName(newName, others)?.let {
            return Result.failure(IllegalArgumentException("name rejected: $it"))
        }
        val saved = SharedFolderMounts.save(others + SharedFolderMount(name = newName, sourcePath = target.sourcePath))
        if (!saved) return Result.failure(IllegalStateException("save failed"))
        recordAlias(oldName, newName)
        if (wasCurrent) select(newName)
        return Result.success(ForgeWorkspace(newName, target.sourcePath))
    }

    /** 移除工作区：只删挂载记录，文件夹与里面的文件都不动；指向它的别名一并清掉，免得删了又被指回来。 */
    fun remove(name: String): Boolean {
        val mounts = SharedFolderMounts.current()
        if (mounts.none { it.name == name }) return false
        if (!SharedFolderMounts.save(mounts.filter { it.name != name })) return false
        clearAliasesPointingTo(name)
        return true
    }

    /** 当前全部挂载的指纹：server 启动时焊入的是当时的挂载集合，指纹变了说明挂载改过、在跑的 server 看不到新挂载。 */
    fun mountsFingerprint(): String =
        SharedFolderMounts.current()
            .joinToString(separator = "|") { "${it.name}=${it.sourcePath}" }
            .hashCode()
            .toString()
}
