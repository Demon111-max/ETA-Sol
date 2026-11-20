package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageLink
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.agent.roleplay.RoleplayReplyRevision
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentConversationPersistenceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        // 草稿走旁路文件（不在数据库里，见 AgentConversationStore 的 AgentDraftStore）：
        // 用例之间若复用临时目录，上一条的草稿会漏到下一条，P3-14 的回归用例对此很敏感。
        context.filesDir?.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun searchSuggestionsStayInLoadedConversationAndNeverReachMessageStorage() = runBlocking {
        val message = AgentMessageUi("search-answer", "正文与引用", searchSuggestionsHtml = listOf("<div>短期建议</div>"))
        val conversation = state("search").copy(messages = listOf(message))
        AgentConversationPersistence().save(context, snapshot(mapOf("search" to conversation)))
        val restored = AgentConversationStore.loadAllMessages(context, "search").filterIsInstance<AgentMessageUi>().single()
        assertEquals(message.content, restored.content)
        assertTrue(restored.searchSuggestionsHtml.isEmpty())
        val unloaded = AgentConversationStore.unloadConversation(conversation)
        assertTrue((unloaded.messages.single() as AgentMessageUi).searchSuggestionsHtml.isEmpty())
    }

    @Test
    fun appendingOnlyWritesTheChangedConversationAndNewMessages() = runBlocking {
        val persistence = AgentConversationPersistence()
        val initial = snapshot(mapOf("active" to state("active"), "dormant" to state("dormant")))
        persistence.save(context, initial)
        installAudit()
        val active = initial.conversationsById.getValue("active")
        val addition = AgentModelClient.ConversationMessage(role = "user", content = "继续")
        val next = initial.copy(
            conversationsById = initial.conversationsById + ("active" to active.copy(
                messages = active.messages + UserMessageUi("new-user", "继续"),
                history = active.history + addition, journal = active.journal + addition,
                appliedRuntimeRunIds = listOf("applied-run"),
            )), updatedAt = initial.updatedAt + ("active" to 9L),
        )

        persistence.save(context, next)

        assertEquals(0, events("conversations", "dormant"))
        assertEquals(0, events("conversation_context_checkpoints", "dormant"))
        assertEquals(0, events("conversation_messages", "active-user"))
        assertEquals(0, events("conversation_messages", "active-thinking"))
        assertEquals(0, events("conversation_messages", "dormant-thinking"))
        assertEquals(1, events("conversation_messages", "new-user"))
        val rows = EtaDatabase.get(context).conversationDao().conversationEntities().associateBy { it.id }
        assertEquals(1L, rows.getValue("active").createdAt)
        assertEquals(9L, rows.getValue("active").updatedAt)
        assertEquals(next.conversationsById.getValue("active").journal, AgentConversationCodec.decodeTranscript(
            EtaDatabase.get(context).conversationDao().contextCheckpoint("active")!!.journalJson))
    }

    @Test
    fun selectionAndDraftChangesDoNotRewriteStoredContent() = runBlocking {
        val persistence = AgentConversationPersistence()
        val initial = snapshot(mapOf("active" to state("active"), "other" to state("other")))
        persistence.save(context, initial)
        installAudit()
        val next = initial.copy(selectedConversationId = "other", conversationsById = initial.conversationsById +
            ("active" to initial.conversationsById.getValue("active").copy(input = "尚未发送的草稿")))

        persistence.save(context, next)

        assertEquals(0, allEvents())
        assertEquals("other", EtaDatabase.get(context).conversationDao().state()?.selectedConversationId)
    }

    @Test
    fun contextCompressionDoesNotRewriteTheUnchangedJournalOrMessages() = runBlocking {
        val persistence = AgentConversationPersistence()
        val initial = snapshot(mapOf("active" to state("active")))
        persistence.save(context, initial)
        installAudit()
        val next = initial.copy(conversationsById = mapOf("active" to initial.conversationsById.getValue("active").copy(
            history = listOf(AgentModelClient.ConversationMessage(role = "assistant", content = "摘要", contextSummary = true)),
        )))

        persistence.save(context, next)

        assertEquals(0, chunkEvents("active", "journal"))
        assertEquals(0, events("conversation_messages", "active-thinking"))
        val restored = EtaDatabase.get(context).conversationDao().contextCheckpoint("active")!!
        assertEquals(initial.conversationsById.getValue("active").journal, AgentConversationCodec.decodeTranscript(restored.journalJson))
        assertEquals("摘要", AgentConversationCodec.decodeTranscript(restored.historyJson).single().content)
    }

    @Test
    fun aFailedTransactionRollsBackAndTheSameWriterCanRetry() = runBlocking {
        val persistence = AgentConversationPersistence()
        val initial = snapshot(mapOf("active" to state("active")))
        persistence.save(context, initial)
        val sql = EtaDatabase.get(context).openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_test_message BEFORE INSERT ON conversation_messages " +
            "WHEN NEW.id = 'rejected-user' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        val next = initial.copy(conversationsById = mapOf("active" to initial.conversationsById.getValue("active").copy(
            messages = initial.conversationsById.getValue("active").messages + UserMessageUi("rejected-user", "新消息"),
            history = listOf(AgentModelClient.ConversationMessage(role = "user", content = "新历史")),
            appliedRuntimeRunIds = listOf("new-run"),
        )), titles = mapOf("active" to "新标题"), updatedAt = mapOf("active" to 10L))
        var failed = false
        try { persistence.save(context, next) } catch (_: Exception) { failed = true }
        assertTrue(failed)
        val dao = EtaDatabase.get(context).conversationDao()
        assertEquals("active", dao.conversations().single().title)
        assertEquals(initial.conversationsById.getValue("active").history,
            AgentConversationCodec.decodeTranscript(dao.contextCheckpoint("active")!!.historyJson))
        assertEquals("[]", dao.conversations().single().appliedRuntimeRunIdsJson)
        sql.execSQL("DROP TRIGGER reject_test_message")

        persistence.save(context, next)

        assertEquals("新标题", dao.conversations().single().title)
        assertEquals(3, dao.messageCount("active"))
        assertEquals(next.conversationsById.getValue("active").history,
            AgentConversationCodec.decodeTranscript(dao.contextCheckpoint("active")!!.historyJson))
        assertEquals("[\"new-run\"]", dao.conversations().single().appliedRuntimeRunIdsJson)
    }

    @Test
    fun deletingAConversationCleansItsChunksAndPreservesOthers() = runBlocking {
        val persistence = AgentConversationPersistence()
        val initial = snapshot(mapOf("active" to state("active"), "deleted" to state("deleted")))
        persistence.save(context, initial)
        installAudit()

        persistence.save(context, initial.copy(conversationsById = initial.conversationsById - "deleted"))

        val dao = EtaDatabase.get(context).conversationDao()
        assertEquals(listOf("active"), dao.conversations().map { it.id })
        assertEquals(0, events("conversation_messages", "active-thinking"))
        val count = EtaDatabase.get(context).openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM agent_text_chunks WHERE owner_id IN ('deleted', 'deleted-thinking')",
        ).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, count)
    }

    @Test
    fun restartAfterSavingAroundAStreamingPlaceholderKeepsEveryMessage() = runBlocking {
        // 空白流式占位不入库，运行中途保存会在 sort_index 中留下空洞；重启后列表被压紧。
        val running = state("active").copy(messages = listOf(
            UserMessageUi("user", "请求"),
            AgentMessageUi(id = "assistant-run", content = "", isStreaming = true, renderMarkdown = false),
            ThinkingMessageUi("thinking", "思考", false),
            ThinkingMessageUi("later", "继续思考", false),
        ))
        AgentConversationPersistence().save(context, snapshot(mapOf("active" to running)))

        val loaded = AgentConversationStore.load(context)
        assertEquals(setOf("active"), loaded.unalignedConversationIds)
        val restored = loaded.conversationsById.getValue("active")
        AgentConversationPersistence(loaded).save(context, loaded.copy(conversationsById = mapOf("active" to
            restored.copy(messages = restored.messages + AgentMessageUi(id = "answer", content = "最终回答")))))

        val reloaded = AgentConversationStore.load(context)
        assertEquals(listOf("user", "thinking", "later", "answer"),
            reloaded.conversationsById.getValue("active").messages.map { it.id })
        assertTrue(reloaded.unalignedConversationIds.isEmpty())
    }

    @Test
    fun appendingAndDeletingWithinATailWindowNeverRewritesTheUnloadedPrefix() = runBlocking {
        val messages = (0 until 300).map { UserMessageUi("user-$it", "消息-$it") }
        AgentConversationPersistence().save(context, snapshot(mapOf("active" to state("active").copy(messages = messages))))
        val loaded = AgentConversationStore.load(context)
        val persistence = AgentConversationPersistence(loaded)
        val tail = loaded.conversationsById.getValue("active")
        assertEquals(172, tail.messageStartIndex)
        installAudit()

        val appended = tail.copy(messages = tail.messages + AgentMessageUi("new-answer", "新回答"))
        persistence.save(context, loaded.copy(conversationsById = mapOf("active" to appended)))

        assertEquals(0, events("conversation_messages", "user-0"))
        assertEquals(0, events("conversation_messages", "user-171"))
        assertEquals(messages + appended.messages.last(), AgentConversationStore.loadAllMessages(context, "active"))

        persistence.save(context, loaded.copy(conversationsById = mapOf("active" to appended.copy(messages = emptyList()))))

        assertEquals(messages.take(172), AgentConversationStore.loadAllMessages(context, "active"))
        assertEquals(0, events("conversation_messages", "user-171"))
    }

    @Test
    fun renamingAnUnloadedConversationPreservesItsMessagesAndContext() = runBlocking {
        val original = snapshot(mapOf("active" to state("active"), "dormant" to state("dormant")))
        AgentConversationPersistence().save(context, original)
        val dormantBefore = AgentConversationStore.loadConversation(context, "dormant")!!
        val loaded = AgentConversationStore.load(context)
        assertTrue(!loaded.conversationsById.getValue("dormant").isConversationLoaded)
        installAudit()

        AgentConversationPersistence(loaded).save(context, loaded.copy(
            titles = loaded.titles + ("dormant" to "新名称"), updatedAt = loaded.updatedAt + ("dormant" to 20L),
        ))

        assertEquals(0, events("conversation_messages", "dormant-thinking"))
        assertEquals(0, events("conversation_context_checkpoints", "dormant"))
        val dormant = AgentConversationStore.loadConversation(context, "dormant")!!
        assertEquals(dormantBefore.messages, dormant.messages)
        assertEquals(dormantBefore.history, dormant.history)
        assertEquals("新名称", EtaDatabase.get(context).conversationDao().conversation("dormant")!!.title)
    }

    @Test
    fun adoptingAnEarlierPageDoesNotRewriteItsUnchangedRows() = runBlocking {
        val original = state("active").copy(messages = (0 until 200).map { UserMessageUi("user-$it", "消息-$it") })
        AgentConversationPersistence().save(context, snapshot(mapOf("active" to original)))
        val loaded = AgentConversationStore.load(context)
        val persistence = AgentConversationPersistence(loaded)
        val state = loaded.conversationsById.getValue("active")
        val page = AgentConversationStore.loadEarlierMessages(context, "active", state.messageStartIndex)
        val expanded = state.copy(messages = page.messages + state.messages, messageStartIndex = page.startIndex,
            hasEarlierMessages = page.hasEarlierMessages)
        persistence.adoptConversation("active", expanded, loaded.titles.getValue("active"), loaded.updatedAt.getValue("active"))
        installAudit()

        persistence.save(context, loaded.copy(conversationsById = mapOf("active" to expanded)))

        assertEquals(0, allEvents())
        assertEquals(original.messages, AgentConversationStore.loadAllMessages(context, "active"))
    }

    @Test
    fun offscreenRoleplayRevisionsUpdateStoredRowsWithoutLoadingTheirMessageWindow() = runBlocking {
        val messages = (0 until 300).map { index ->
            if (index % 2 == 0) UserMessageUi("message-$index", "正文-$index")
            else AgentMessageUi("message-$index", "正文-$index")
        }
        val original = state("active").copy(
            messages = messages,
            roleplay = RoleplayBinding("character", "{}", "旅人"),
            roleplayMessages = RoleplayMessageState(links = messages.associate { it.id to RoleplayMessageLink(it.id) }),
        )
        AgentConversationPersistence().save(context, snapshot(mapOf("active" to original)))
        val loaded = AgentConversationStore.load(context)
        val tail = loaded.conversationsById.getValue("active")
        assertEquals(172, tail.messageStartIndex)
        val editedContent = "修订".repeat(10_000)
        val revised = tail.copy(roleplayMessages = tail.roleplayMessages.copy(revisions = mapOf(
            "message-0" to RoleplayReplyRevision("正文-0", listOf("正文-0", editedContent), 1),
            "message-1" to RoleplayReplyRevision("正文-1", listOf("正文-1", "新回答"), 1),
        )))

        AgentConversationPersistence(loaded).save(context, loaded.copy(conversationsById = mapOf("active" to revised)))

        val allMessages = AgentConversationStore.loadAllMessages(context, "active")
        assertEquals(300, allMessages.size)
        assertEquals(UserMessageUi("message-0", editedContent, isEdited = true), allMessages[0])
        assertEquals(AgentMessageUi("message-1", "新回答"), allMessages[1])
        assertEquals(messages.drop(2), allMessages.drop(2))
        assertEquals(setOf("active"), AgentConversationStore.searchConversationIds(context, "新回答"))
    }

    @Test
    fun aNewOffscreenRevisionWithAMissingTargetRollsBackWithoutRejectingOldMetadata() = runBlocking {
        val original = state("active").copy(
            roleplay = RoleplayBinding("character", "{}", "旅人"),
            roleplayMessages = RoleplayMessageState(revisions = mapOf(
                "legacy-missing" to RoleplayReplyRevision("旧正文", listOf("旧正文"), 0),
            )),
        )
        AgentConversationPersistence().save(context, snapshot(mapOf("active" to original)))
        val loaded = AgentConversationStore.load(context)
        val persistence = AgentConversationPersistence(loaded)
        val current = loaded.conversationsById.getValue("active")
        val revised = current.copy(roleplayMessages = current.roleplayMessages.copy(revisions =
            current.roleplayMessages.revisions + ("missing-target" to RoleplayReplyRevision("原文", listOf("原文", "修改"), 1))))
        var failed = false
        try {
            persistence.save(context, loaded.copy(conversationsById = mapOf("active" to revised), titles = mapOf("active" to "不应提交")))
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        val restored = AgentConversationStore.loadConversation(context, "active")!!
        assertEquals(current.roleplayMessages, restored.roleplayMessages)
        assertEquals("active", EtaDatabase.get(context).conversationDao().conversation("active")!!.title)

        persistence.save(context, loaded.copy(titles = mapOf("active" to "合法改名")))
        assertEquals("合法改名", EtaDatabase.get(context).conversationDao().conversation("active")!!.title)
        assertEquals(current.roleplayMessages, AgentConversationStore.loadConversation(context, "active")!!.roleplayMessages)
    }

    @Test
    fun fallbackClampIsNeverWrittenBackToTheArchivedEffort() = runBlocking {
        // 会话绑 M1(MAX) → M1 被禁用 → 显示与本次发送钳到 HIGH：存档里必须还是 MAX，
        // 否则重新启用 M1 后原档位就没了（3.4.50 P1-2）。
        val clamped = state("active").copy(
            reasoningEffort = ReasoningEffort.HIGH,
            archivedReasoningEffort = ReasoningEffort.MAX,
            thinkingEnabled = true,
        )

        AgentConversationPersistence().save(context, snapshot(mapOf("active" to clamped)))

        val row = EtaDatabase.get(context).conversationDao().conversation("active")!!
        assertEquals(ReasoningEffort.MAX.wireValue, row.reasoningEffort)
        assertTrue(row.thinkingEnabled)
        // 模型恢复可用后重新载入，档位回到原档。
        assertEquals(ReasoningEffort.MAX, AgentConversationStore.loadConversation(context, "active")!!.reasoningEffort)
    }

    @Test
    fun unclampedEffortIsStoredAsIs() = runBlocking {
        val state = state("active").copy(
            reasoningEffort = ReasoningEffort.LOW,
            thinkingEnabled = true,
        )

        AgentConversationPersistence().save(context, snapshot(mapOf("active" to state)))

        assertEquals(ReasoningEffort.LOW.wireValue,
            EtaDatabase.get(context).conversationDao().conversation("active")!!.reasoningEffort)
    }

    @Test
    fun contentKeepsThePinnedAndLoadedFlagsInTheirOwnFields() {
        // Content 的 13 个实参里有两个 Boolean：isPinned 在第 3 位、isConversationLoaded 在第 12 位。
        // 位置传参看走眼就是把「置顶」写成「已载入」，编译器不会报错（3.4.50 审理 P3-17）。
        // 这里逐字段核对一遍，锁住字段顺序与 journal 的空值兜底。
        val source = state("conv-a").copy(
            isConversationLoaded = false,
            messageStartIndex = 42,
            modelId = "model-x",
            journal = emptyList(),
        )

        val content = AgentConversationPersistence.Content(
            state = source,
            title = "标题",
            updatedAt = 7L,
            isPinned = true,
        )

        assertTrue(content.isPinned)
        assertFalse(content.isConversationLoaded)
        assertEquals(42, content.messageStartIndex)
        assertEquals("model-x", content.modelId)
        assertEquals("标题", content.title)
        assertEquals(7L, content.updatedAt)
        assertEquals(source.reasoningEffort, content.reasoningEffort)
        assertEquals(source.messages, content.messages)
        // journal 为空时落盘取 history，否则上下文压缩后重启会丢掉整段历史。
        assertEquals(source.history, content.journal)
    }

    @Test
    fun unsentDraftSurvivesARestartWithoutRewritingStoredContent() = runBlocking {
        // 3.4.50 审理 P3-14：conversations 表没有草稿列，草稿走旁路文件；
        // 但「重启后草稿还在」必须成立——load 与 loadConversation 两条回读路径都要带上它。
        val persistence = AgentConversationPersistence()
        val initial = snapshot(mapOf("active" to state("active"), "other" to state("other")))
        persistence.save(context, initial)
        installAudit()
        val typed = initial.copy(conversationsById = initial.conversationsById +
            ("active" to initial.conversationsById.getValue("active").copy(input = "打了一半的字")))

        persistence.save(context, typed)

        // 草稿不许把正文行重写一遍（否则每 200ms 敲字都要重写会话元数据）。
        assertEquals(0, allEvents())
        // 模拟进程死亡后重新启动：全新的 load 必须把草稿读回来。
        val reloaded = AgentConversationStore.load(context)
        assertEquals("打了一半的字", reloaded.conversationsById.getValue("active").input)
        assertEquals("打了一半的字", AgentConversationStore.loadConversation(context, "active")!!.input)
        assertEquals("", reloaded.conversationsById.getValue("other").input)
    }

    @Test
    fun draftsOfUnloadedConversationsAreNotSilentlyDropped() = runBlocking {
        // 3.4.50 审理 P3-16（持久化侧）：未载入的会话在正文事务里只写标题/置顶/时间，其余整段 continue。
        // 草稿只存在于旁路文件里，所以整份草稿表必须一起写——按「本次改动的会话」增量写，
        // 未载入会话的草稿就被静默抹掉了。
        val original = snapshot(mapOf(
            "active" to state("active"),
            "dormant" to state("dormant").copy(input = "dormant 的草稿"),
        ))
        AgentConversationPersistence().save(context, original)
        val loaded = AgentConversationStore.load(context)
        assertFalse(loaded.conversationsById.getValue("dormant").isConversationLoaded)
        assertEquals("dormant 的草稿", loaded.conversationsById.getValue("dormant").input)

        // 这次只改 active 的草稿与 dormant 的标题：dormant 的草稿不在改动集里，也不许丢。
        val next = loaded.copy(
            conversationsById = loaded.conversationsById +
                ("active" to loaded.conversationsById.getValue("active").copy(input = "active 的新草稿")),
            titles = loaded.titles + ("dormant" to "改个名"),
        )
        AgentConversationPersistence(loaded).save(context, next)

        val restored = AgentConversationStore.load(context)
        assertEquals("active 的新草稿", restored.conversationsById.getValue("active").input)
        assertEquals("dormant 的草稿", restored.conversationsById.getValue("dormant").input)
        assertEquals("dormant 的草稿", AgentConversationStore.loadConversation(context, "dormant")!!.input)
        assertEquals("改个名", EtaDatabase.get(context).conversationDao().conversation("dormant")!!.title)
    }

    private fun state(id: String): AgentChatHomeUiState {
        val history = listOf(AgentModelClient.ConversationMessage(role = "assistant", content = "历史".repeat(12_000)))
        return AgentChatHomeUiState(
            messages = listOf(UserMessageUi("$id-user", "请求"),
                ThinkingMessageUi("$id-thinking", "思考".repeat(12_000), false)),
            history = history, journal = history, input = "", isStreaming = false, thinkingEnabled = false,
        )
    }

    private fun snapshot(states: Map<String, AgentChatHomeUiState>) = AgentConversationStore.Snapshot(
        "active", states, states.keys.associateWith { it }, states.keys.associateWith { 1L },
    )

    private fun installAudit() {
        val sql = EtaDatabase.get(context).openHelper.writableDatabase
        sql.execSQL("CREATE TABLE mutation_log (table_name TEXT, owner_id TEXT, field TEXT)")
        for ((table, key) in mapOf("conversations" to "id", "conversation_messages" to "id", "conversation_context_checkpoints" to "conversation_id")) {
            for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
                val row = if (operation == "DELETE") "OLD" else "NEW"
                sql.execSQL("CREATE TRIGGER audit_${table}_$operation AFTER $operation ON $table BEGIN " +
                    "INSERT INTO mutation_log VALUES ('$table', $row.$key, ''); END")
            }
        }
        for (operation in listOf("INSERT", "DELETE")) {
            val row = if (operation == "DELETE") "OLD" else "NEW"
            sql.execSQL("CREATE TRIGGER audit_chunks_$operation AFTER $operation ON agent_text_chunks BEGIN " +
                "INSERT INTO mutation_log VALUES ('agent_text_chunks', $row.owner_id, $row.field); END")
        }
    }

    private fun events(table: String, owner: String): Int =
        count("SELECT COUNT(*) FROM mutation_log WHERE table_name = ? AND owner_id = ?", arrayOf(table, owner))

    private fun chunkEvents(owner: String, field: String): Int =
        count("SELECT COUNT(*) FROM mutation_log WHERE table_name = 'agent_text_chunks' AND owner_id = ? AND field = ?", arrayOf(owner, field))

    private fun allEvents(): Int = count("SELECT COUNT(*) FROM mutation_log", emptyArray())

    private fun count(sql: String, args: Array<Any?>): Int = EtaDatabase.get(context).openHelper.readableDatabase
        .query(sql, args).use { it.moveToFirst(); it.getInt(0) }
}
