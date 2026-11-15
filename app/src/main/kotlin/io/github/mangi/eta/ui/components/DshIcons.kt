package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import io.github.mangi.eta.R

/**
 * DeepSeek Harness（deepseek-ai/deepseek-harness，MIT 许可）的对话区图标，
 * 按其 artwork 原始 SVG 路径复刻为矢量资源（res/drawable/ic_dsh_*）：
 * 16 单位画布、描边宽 1，使用时以 14dp 渲染在 16dp 槽位内，与原版一致。
 */
@Composable
internal fun DshThinkIcon(): ImageVector = ImageVector.vectorResource(R.drawable.ic_dsh_think)

@Composable
internal fun DshChevronUpIcon(): ImageVector = ImageVector.vectorResource(R.drawable.ic_dsh_chevron_up)

@Composable
internal fun DshChevronDownIcon(): ImageVector = ImageVector.vectorResource(R.drawable.ic_dsh_chevron_down)

/**
 * 工具图标按 dsh 的分工映射（ui-tool 的 toolviews/details-row）：
 * 命令执行=api（终端提示符字形）、读取=browse、写入/编辑=edit、搜索=search、
 * 网页搜索=globe、终端类=code、待办=checklist、子智能体=users、提问=question、
 * 等待/定时=clock、技能=sparkle，其余一律 agent-preset（dsh 明细行的通用回落）。
 * 同时覆盖 Eta 本机工具名与 opencode 工具名。
 */
@Composable
internal fun dshIconForTool(toolId: String): ImageVector {
    val res = when {
        toolId in BASH_TOOLS -> R.drawable.ic_dsh_api
        toolId in CODE_TOOLS -> R.drawable.ic_dsh_code
        toolId in READ_TOOLS || toolId in BROWSER_TOOLS -> R.drawable.ic_dsh_browse
        toolId in WRITE_TOOLS -> R.drawable.ic_dsh_edit
        toolId in WEB_SEARCH_TOOLS -> R.drawable.ic_dsh_globe
        toolId in TODO_TOOLS -> R.drawable.ic_dsh_checklist
        toolId in AGENT_TOOLS -> R.drawable.ic_dsh_users
        toolId in QUESTION_TOOLS -> R.drawable.ic_dsh_question
        toolId in CLOCK_TOOLS || toolId.startsWith("schedule_") -> R.drawable.ic_dsh_clock
        toolId in SEARCH_TOOLS || toolId.startsWith("search_") -> R.drawable.ic_dsh_search
        toolId.startsWith("skills_") || toolId == "skill" -> R.drawable.ic_dsh_sparkle
        else -> R.drawable.ic_dsh_agent_preset
    }
    return ImageVector.vectorResource(res)
}

private val BASH_TOOLS = setOf(
    "bash", "shell", "exec",
    "code_interpreter", "code_interpreter_call", "代码执行",
)

// 终端类归 code（dsh 原版分工）：Eta 的 terminal 系列与 run_command 都在这里，
// 不能放进 BASH_TOOLS，否则会被前面的 api 分支先命中。
private val CODE_TOOLS = setOf(
    "computer", "computer_call", "计算机操作",
    "terminal", "terminal_job", "terminal_daemon", "run_command", "get_logcat",
)

private val READ_TOOLS = setOf(
    "read", "list", "ls", "read_file", "stat_file", "read_image", "observe", "observe_screen",
    "browser_read", "fetch_url", "webfetch", "web_fetch",
)

private val BROWSER_TOOLS = setOf(
    "browser_use", "browser_interact", "browser_screenshot", "网页浏览",
    // Eta 的手机界面操作：看屏幕、点按滑动，与「浏览界面」同义，归 browse。
    "click", "tap", "tap_element", "long_press", "swipe", "scroll", "get_current_context",
)

private val WRITE_TOOLS = setOf(
    "write", "edit", "patch", "apply_patch", "multiedit",
    "write_file", "edit_file", "replace_text",
    // Eta 的文字输入类工具，本质是往界面里写字，归 edit。
    "type_text", "input_text", "clear_text",
)

private val SEARCH_TOOLS = setOf(
    "grep", "glob", "find", "file_search", "file_search_call", "文件搜索",
    "grep_files", "glob_files", "list_directory", "search_files",
)

private val WEB_SEARCH_TOOLS = setOf("web_search", "web_search_call", "websearch", "网页搜索")

private val TODO_TOOLS = setOf("todo", "todowrite", "todoread")

private val AGENT_TOOLS = setOf("task", "subagent", "subagent_profile")

private val QUESTION_TOOLS = setOf("question", "ask_question", "ask_user_question")

private val CLOCK_TOOLS = setOf(
    "wait", "wait_text", "wait_for_text", "wait_for_package",
    "set_alarm", "set_timer", "list_alarms", "list_active_timers",
)
