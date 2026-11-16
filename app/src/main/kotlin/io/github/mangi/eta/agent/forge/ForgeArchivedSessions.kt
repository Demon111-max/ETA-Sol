package io.github.mangi.eta.agent.forge

import io.github.mangi.eta.agent.terminal.PrefsWriteLock
import io.github.mangi.eta.config.Prefs

/**
 * Forge 会话的本地归档记录（只存会话 id 集合）：opencode 没有给侧边栏用的归档开关，
 * 归档先做在 App 侧——归档的会话默认不在侧边栏显示，用「显示已归档」开关调出来，
 * 取消归档即恢复。会话本体与服务端数据都不动。
 */
internal object ForgeArchivedSessions {
    private const val PREFS_KEY = "forge_archived_session_ids"

    fun all(): Set<String> = runCatching {
        Prefs.localAgentPreferences()?.getStringSet(PREFS_KEY, emptySet())
    }.getOrNull()?.toSet() ?: emptySet()

    fun setArchived(sessionId: String, archived: Boolean) {
        synchronized(PrefsWriteLock.lock) {
            val updated = all().toMutableSet()
            if (archived) {
                updated += sessionId
            } else {
                updated -= sessionId
            }
            runCatching {
                Prefs.localAgentPreferences()?.edit()?.putStringSet(PREFS_KEY, updated)?.apply()
            }
        }
    }

    /**
     * 只保留仍然存在的会话 id：归档表只增不减，会话删掉后 id 会在 prefs 里永远躺着，无界增长。
     * 幂等；写入失败静默（与 [setArchived] 同一风格）；与 [setArchived] 共用同一把写锁。
     */
        fun prune(validIds: Set<String>) {
        synchronized(PrefsWriteLock.lock) {
            val existing = all()
            val kept = existing.intersect(validIds)
            if (kept.size == existing.size) return@synchronized
            runCatching {
                Prefs.localAgentPreferences()?.edit()?.putStringSet(PREFS_KEY, kept)?.apply()
            }
        }
    }
}