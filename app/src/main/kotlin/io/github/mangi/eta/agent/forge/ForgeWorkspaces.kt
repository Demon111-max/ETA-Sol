package io.github.mangi.eta.agent.forge

import io.github.mangi.eta.agent.terminal.SharedFolderMount
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.config.Prefs
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
     * 确保默认工作区存在：已有同名挂载直接用；已有指向同一目录的挂载就用那条（不重复建）；
     * 都没有就建目录 + 追加挂载。动不了既有挂载。返回默认工作区，实在建不出来返回 null。
     * 只在从没建过时自动建：用户主动移除过默认工作区就别再复活它，不然永远删不掉。
     */
    fun ensureDefault(): ForgeWorkspace? {
        val mounts = SharedFolderMounts.current()
        mounts.firstOrNull { it.name == DEFAULT_NAME }
            ?.let {
                markDefaultCreated()
                return ForgeWorkspace(it.name, it.sourcePath)
            }
        mounts.firstOrNull { it.sourcePath == DEFAULT_SOURCE }
            ?.let {
                markDefaultCreated()
                return ForgeWorkspace(it.name, it.sourcePath)
            }
        if (defaultCreated()) return null
        runCatching { File(DEFAULT_SOURCE).mkdirs() }
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
        var name = SharedFolderMounts.defaultName(source)
        var suffix = 2
        while (SharedFolderMounts.validateName(name, mounts) != null) {
            name = "${SharedFolderMounts.defaultName(source).take(44)}-$suffix"
            suffix++
            if (suffix > 32) return Result.failure(IllegalArgumentException("cannot derive unique name"))
        }
        runCatching { File(source).mkdirs() }
        val workspace = ForgeWorkspace(name = name, sourcePath = source)
        val saved = SharedFolderMounts.save(mounts + SharedFolderMount(name = name, sourcePath = source))
        return if (saved) Result.success(workspace) else Result.failure(IllegalStateException("save failed"))
    }

    /** 改名：只改挂载名（Linux 路径随之变），目录内容不动；改的是当前工作区时选择跟着走。 */
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
        if (wasCurrent) select(newName)
        return Result.success(ForgeWorkspace(newName, target.sourcePath))
    }

    /** 移除工作区：只删挂载记录，文件夹与里面的文件都不动。 */
    fun remove(name: String): Boolean {
        val mounts = SharedFolderMounts.current()
        if (mounts.none { it.name == name }) return false
        return SharedFolderMounts.save(mounts.filter { it.name != name })
    }

    /** 当前全部挂载的指纹：server 启动时焊入的是当时的挂载集合，指纹变了说明挂载改过、在跑的 server 看不到新挂载。 */
    fun mountsFingerprint(): String =
        SharedFolderMounts.current()
            .joinToString(separator = "|") { "${it.name}=${it.sourcePath}" }
            .hashCode()
            .toString()
}
