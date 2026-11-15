package io.github.mangi.eta.ui.app

import android.app.Application
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 最小复现：ui\app\AgentConversationStore.kt:66-69 + 261-264
 *
 *     fun load(context: Context): Snapshot =
 *         runBlocking(Dispatchers.IO) { loadSnapshot(context.applicationContext) }
 *     ...
 *     roleplay = conversation.roleplayJson.takeIf(String::isNotBlank)
 *         ?.let { json.decodeFromString<RoleplayBinding>(it) },
 *
 * `load()` 在 ViewModel 构造期被同步调用（AgentAppState.kt:102 的默认参数），
 * 而这条链上没有任何 try/catch：`conversations` 表里只要有一行 roleplayJson 与当前
 * RoleplayBinding 结构不兼容（升级删改字段、写入被截断、外部导入），
 * kotlinx.serialization 抛出的 MissingFieldException 会一路穿出 ViewModel 构造函数。
 *
 * 本测试断言「坏数据不应让启动加载失败」，因此在当前代码上应当失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentConversationStoreReproTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun incompatibleRoleplayJsonDoesNotBreakStartupLoad() {
        val dao = EtaDatabase.get(context).conversationDao()
        runBlocking {
            dao.insertConversations(
                listOf(
                    ConversationEntity(
                        id = "conv-1",
                        title = "历史会话",
                        thinkingEnabled = false,
                        // RoleplayBinding 需要 characterId / cardSnapshotJson / characterName，
                        // 这里模拟旧版本遗留行：只留下 characterId。
                        roleplayJson = """{"characterId":"char-1"}""",
                        createdAt = 1L,
                        updatedAt = 1L,
                    ),
                ),
            )
        }

        try {
            AgentConversationStore.load(context)
        } catch (failure: Throwable) {
            fail(
                "单行坏 roleplayJson 不应让 AgentConversationStore.load 抛异常，实际抛出：" +
                    "${failure::class.java.name}: ${failure.message}",
            )
        }
    }
}
