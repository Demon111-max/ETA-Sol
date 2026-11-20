package io.github.mangi.eta.agent.forge

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.runtimeProviderType
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Forge 上一次真正用过的「模型 + 思考档位」。
 *
 * Forge 的档位原来每开一个新对话都退回 DEFAULT，再靠模型声明播种；没声明能力的模型就永远停在
 * 「默认」，用户每开一次新对话都要重选一遍模型和档位。这份存档把「上次实际用的」记下来，
 * 新对话直接接着用（3.4.52）。
 *
 * 只记 Forge 自己的选择：Assist 不读也不写它，Assist 新对话仍按声明播种。
 */
internal data class ForgeLastSelection(
    val modelId: String,
    val effort: ReasoningEffort,
)

/**
 * 新对话该怎么开：沿用存档，还是走今天的老规矩。
 *
 * 抽成纯决策函数是为了把「恢复 / 退回 / 归一化」三类判断独立测——它们决定了用户新开对话时
 * 看到的模型与档位，出错的表现是「模型被悄悄换掉」或「档位发不出去」，都是线上最难查的那类。
 */
internal sealed interface ForgeRestoreDecision {
    /** 存档不可用：不动任何状态，走「共享当前模型 + 声明播种」。 */
    data object Keep : ForgeRestoreDecision

    /**
     * 恢复成存档的模型与档位（档位已按该模型能力归一化）。
     *
     * [modelId] 是模型名——存档里的稳定标识，也是写进 opencode 配置的那一个；[rowId] 是模型在库里的
     * 行 id，写共享选择（`RuntimeConfigRepository.setSelectedModelId`）要的是它。两者不能混用：
     * 存档存名字是因为目录重新合并后行 id 会变、名字不变。
     */
    data class Restore(
        val modelId: String,
        val rowId: String,
        val effort: ReasoningEffort,
    ) : ForgeRestoreDecision
}

/**
 * 存档模型在共享配置里解析出来的样子。
 *
 * [capabilities] 用 effective 口径（`RuntimeConfigRepository.effectiveReasoningCapabilities`）：
 * 能力未知时是那份放行全部档位的回落表，明确不支持思考时才是 null。
 */
internal data class ForgeRestoreModel(
    val modelId: String,
    val rowId: String,
    val capabilities: ModelReasoningCapabilities?,
)

/**
 * 恢复决策：存档模型还在、还启用、Forge 还能用它 → 恢复；否则整份作废。
 *
 * 档位一定过一遍 [ModelReasoningCapabilities.normalize]：存档档位可能来自旧版本、或该模型后来
 * 收窄了档位集，收敛到可选档而不是抛错（Forge 发送侧对不可选档会撞服务商硬表，整轮失败）。
 * 能力为 null（模型被明确声明不支持思考）时落 DEFAULT——与 Forge 现行口径一致：不发 variant。
 */
internal fun forgeRestoreDecision(
    saved: ForgeLastSelection?,
    model: ForgeRestoreModel?,
): ForgeRestoreDecision {
    if (saved == null || model == null) return ForgeRestoreDecision.Keep
    if (saved.modelId != model.modelId) return ForgeRestoreDecision.Keep
    val effort = model.capabilities?.normalize(saved.effort) ?: ReasoningEffort.DEFAULT
    return ForgeRestoreDecision.Restore(modelId = model.modelId, rowId = model.rowId, effort = effort)
}

/**
 * 存档里的模型现在还能不能用：服务商与模型都启用、且 Forge 能连（OpenAI 兼容 + API Key 鉴权）。
 *
 * 与共享配置解析同一套判定，但只针对存档里的那一个模型——不能用就返回 null，由
 * [forgeRestoreDecision] 退回「共享当前模型 + 声明播种」。放在这里而不是 ForgeChatState 里，
 * 是因为开屏预热也要做同一个判断（见 `prewarmForgeServer`），两处口径必须同一份。
 */
internal suspend fun forgeRestoreModelFor(modelId: String): ForgeRestoreModel? {
    val name = modelId.trim()
    if (name.isEmpty()) return null
    // 按模型名找，不是按行 id：存档存的是模型名（目录重新合并后行 id 会变、名字不变），
    // 而 ProviderRepository 的 enabledModel() 匹配的是行 id，两者不是同一个东西。
    for (provider in ProviderRepository.allProviders()) {
        if (!provider.isEnabled) continue
        val model = provider.models.firstOrNull { it.isEnabled && it.modelId == name } ?: continue
        if (provider.runtimeProviderType != ProviderTypes.OPENAI_COMPATIBLE) continue
        if (provider.authMode != ProviderAuthModes.API_KEY) continue
        return ForgeRestoreModel(
            modelId = model.modelId,
            rowId = model.id,
            capabilities = RuntimeConfigRepository.effectiveReasoningCapabilities(provider, model),
        )
    }
    return null
}

/**
 * 新对话（同一进程内换会话）要不要沿用存档档位：只有存档属于当前模型时才沿用。
 *
 * 进程重建那一路由 [forgeRestoreDecision] 负责（连模型一起恢复）；这里只管「模型没变、只是开了
 * 新对话」——用户上一轮亲手选的档位不该被播种打回声明默认档。返回 null 表示交给声明播种。
 */
internal fun forgeArchivedEffortForModel(
    modelId: String?,
    saved: ForgeLastSelection?,
    capabilities: ModelReasoningCapabilities?,
): ReasoningEffort? {
    if (modelId.isNullOrEmpty() || saved == null || saved.modelId != modelId) return null
    return capabilities?.normalize(saved.effort) ?: ReasoningEffort.DEFAULT
}

/**
 * 「Forge 上次选择」的落盘处：`filesDir/forge_last_selection.json` 一个小文件。
 *
 * 照 3.4.51 的 AgentDraftStore 先例：不进 Room（不加表、不加迁移）、不走 Xposed 远程偏好
 * （那份是给被 hook 的应用读的运行时配置，与 UI 的本地选择无关）。读坏了就当没有——
 * 这份存档只是「省一次手选」，任何异常都不许影响 Forge 启动。
 */
internal object ForgeLastSelectionStore {
    private const val FileName = "forge_last_selection.json"
    private const val KeyModelId = "modelId"
    private const val KeyEffort = "reasoningEffort"

    private val mutex = Mutex()

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FileName)

    fun load(context: Context): ForgeLastSelection? = try {
        val target = file(context)
        if (target.isFile) parse(target.readText()) else null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        AndroidAgentLogger.warnThrottled("forge_last_selection_read_failed") {
            "读取 Forge 上次选择失败，按无存档处理 type=${failure.javaClass.simpleName}"
        }
        null
    }

    /** 解析存档；任何不认识的内容都当没有（不抛错、不猜）。 */
    internal fun parse(raw: String?): ForgeLastSelection? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val json = try {
            JSONObject(text)
        } catch (failure: Exception) {
            return null
        }
        val modelId = json.optString(KeyModelId, "").trim()
        val effort = ReasoningEffort.fromWireValue(json.optString(KeyEffort, ""))
        if (modelId.isEmpty() || effort == null) return null
        return ForgeLastSelection(modelId = modelId, effort = effort)
    }

    internal fun encode(selection: ForgeLastSelection): String = JSONObject()
        .put(KeyModelId, selection.modelId)
        .put(KeyEffort, selection.effort.wireValue)
        .toString()

    suspend fun save(context: Context, selection: ForgeLastSelection) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val target = file(context)
            val temporary = File(target.parentFile, "$FileName.tmp")
            try {
                temporary.writeText(encode(selection))
                if (!temporary.renameTo(target)) {
                    // 部分文件系统不允许覆盖式 rename：删掉目标再重命名，别让存档悄悄停在旧值。
                    target.delete()
                    temporary.renameTo(target)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AndroidAgentLogger.warnThrottled("forge_last_selection_write_failed") {
                    "写入 Forge 上次选择失败 type=${failure.javaClass.simpleName}"
                }
                temporary.delete()
            }
        }
    }

    suspend fun clear(context: Context) = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                file(context).delete()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AndroidAgentLogger.warnThrottled("forge_last_selection_clear_failed") {
                    "清除 Forge 上次选择失败 type=${failure.javaClass.simpleName}"
                }
            }
        }
    }
}
