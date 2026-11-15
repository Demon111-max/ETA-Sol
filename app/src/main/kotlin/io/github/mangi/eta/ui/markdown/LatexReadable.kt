package io.github.mangi.eta.ui.markdown

/**
 * 把 LaTeX 公式源码换成能直接读的文本：命令换 Unicode 符号（\le → ≤、\times → ×、
 * \rightarrow → →）、分数变 a/b、根号变 √、上下标变 Unicode 上下标、
 * \text{...} 一类包装只留内容、分组花括号去掉。
 *
 * 渲染层没有公式排版引擎：原始 LaTeX 露着反斜杠没人看得懂，给可读文本更实在。
 * 只做符号层面的替换，不求排版还原；不认识的命令去掉反斜杠保留名字，不丢内容。
 */
internal object LatexReadable {

    /** 文本里是否含已知的 LaTeX 数学命令（公式被写进反引号时，据此不当代码渲染）。 */
    fun containsLatexCommand(text: String): Boolean {
        var index = text.indexOf('\\')
        while (index >= 0) {
            val name = commandNameAt(text, index)
            if (name != null && (name in SYMBOLS || name in WRAPPERS || name in FRACTION_COMMANDS || name == "sqrt")) {
                return true
            }
            index = text.indexOf('\\', index + 1)
        }
        return false
    }

    fun convert(source: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < source.length) {
            val char = source[index]
            when {
                char == '\\' -> {
                    val name = commandNameAt(source, index)
                    if (name == null) {
                        // 反斜杠 + 单个非字母字符（\%、\{、\, 等）：按符号表换，查不到就丢掉反斜杠。
                        val single = source.getOrNull(index + 1)
                        val mapped = single?.let { SYMBOLS[it.toString()] }
                        if (mapped != null) {
                            out.append(mapped)
                            index += 2
                        } else {
                            index += 1
                        }
                    } else {
                        val after = index + 1 + name.length
                        when {
                            name in FRACTION_COMMANDS -> {
                                val first = braced(source, after)
                                val second = first?.let { braced(source, it.second) }
                                if (first != null && second != null) {
                                    out.append(fraction(convert(first.first), convert(second.first)))
                                    index = second.second
                                } else {
                                    index = after
                                }
                            }

                            name == "sqrt" -> {
                                val arg = braced(source, after)
                                if (arg != null) {
                                    val inner = convert(arg.first)
                                    out.append(if (inner.length <= 1) "√$inner" else "√($inner)")
                                    index = arg.second
                                } else {
                                    out.append('√')
                                    index = after
                                }
                            }

                            name in WRAPPERS -> {
                                val arg = braced(source, after)
                                if (arg != null) {
                                    out.append(convert(arg.first))
                                    index = arg.second
                                } else {
                                    index = after
                                }
                            }

                            else -> {
                                out.append(SYMBOLS[name] ?: name)
                                index = after
                            }
                        }
                    }
                }

                char == '^' || char == '_' -> {
                    val bracedArg = braced(source, index + 1)
                    val single = source.getOrNull(index + 1)
                    val raw: String
                    val next: Int
                    if (bracedArg != null) {
                        raw = bracedArg.first
                        next = bracedArg.second
                    } else if (single != null && single != ' ' && single != '{') {
                        raw = single.toString()
                        next = index + 2
                    } else {
                        out.append(char)
                        index += 1
                        continue
                    }
                    val inner = convert(raw)
                    val mapped = if (char == '^') superscript(inner) else subscript(inner)
                    if (mapped != null) {
                        out.append(mapped)
                    } else {
                        // 上下标字符映射不全时退回普通写法（如 x^abc），总比露原始花括号强。
                        out.append(char)
                        out.append(inner)
                    }
                    index = next
                }

                // 分组花括号本身不显示。
                char == '{' || char == '}' -> index += 1

                else -> {
                    out.append(char)
                    index += 1
                }
            }
        }
        return out.toString().replace(SPACE_RUN, " ").trim()
    }

    /** 读反斜杠后的命令名：连续字母；后跟非字母时返回 null（单字符命令）。 */
    private fun commandNameAt(text: String, backslashIndex: Int): String? {
        val start = backslashIndex + 1
        if (start >= text.length || !text[start].isLetter()) return null
        var end = start
        while (end < text.length && text[end].isLetter()) end += 1
        return text.substring(start, end)
    }

    /** 读紧跟的一个 {...} 参数（允许前面有空格）：返回内容与结束后的下标，不是花括号开头返回 null。 */
    private fun braced(text: String, from: Int): Pair<String, Int>? {
        var index = from
        while (index < text.length && text[index] == ' ') index += 1
        if (index >= text.length || text[index] != '{') return null
        var depth = 0
        var cursor = index
        while (cursor < text.length) {
            when (text[cursor]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(index + 1, cursor) to (cursor + 1)
                }
            }
            cursor += 1
        }
        return null
    }

    private fun fraction(numerator: String, denominator: String): String {
        val complex = listOf(numerator, denominator).any {
            it.length > 3 || it.any { char -> char == ' ' || char == '+' || char == '-' || char == '=' }
        }
        return if (complex) "($numerator)/($denominator)" else "$numerator/$denominator"
    }

    private fun superscript(text: String): String? {
        if (text.isEmpty()) return null
        val out = StringBuilder()
        for (char in text) out.append(SUPERSCRIPT[char] ?: return null)
        return out.toString()
    }

    private fun subscript(text: String): String? {
        if (text.isEmpty()) return null
        val out = StringBuilder()
        for (char in text) out.append(SUBSCRIPT[char] ?: return null)
        return out.toString()
    }

    private val FRACTION_COMMANDS = setOf("frac", "dfrac", "tfrac")

    /** 只起包装作用的命令：参数内容原样保留，命令本身去掉。 */
    private val WRAPPERS = setOf(
        "text", "mathrm", "mathbf", "mathit", "texttt", "textbf", "textit", "operatorname", "boxed",
    )

    private val SPACE_RUN = Regex("\\s+")

    private val SUPERSCRIPT = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ',
    )

    private val SUBSCRIPT = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'o' to 'ₒ', 'x' to 'ₓ', 'h' to 'ₕ',
        'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'p' to 'ₚ',
        's' to 'ₛ', 't' to 'ₜ',
    )

    /** 命令名 → 可读符号；值为空串的命令（\left、\displaystyle 等）直接消失。 */
    private val SYMBOLS = mapOf(
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
        "ne" to "≠", "neq" to "≠", "lt" to "<", "gt" to ">",
        "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓",
        "cdot" to "·", "cdots" to "⋯", "ldots" to "…",
        "infty" to "∞", "in" to "∈", "notin" to "∉", "ni" to "∋",
        "subset" to "⊂", "supset" to "⊃", "subseteq" to "⊆", "supseteq" to "⊇",
        "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "varnothing" to "∅",
        "forall" to "∀", "exists" to "∃", "neg" to "¬", "land" to "∧", "lor" to "∨",
        "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
        "leftrightarrow" to "↔", "Leftrightarrow" to "⇔", "to" to "→", "gets" to "←",
        "mapsto" to "↦", "approx" to "≈", "equiv" to "≡", "sim" to "∼",
        "simeq" to "≃", "cong" to "≅", "propto" to "∝",
        "perp" to "⊥", "parallel" to "∥", "angle" to "∠", "triangle" to "△",
        "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮",
        "partial" to "∂", "nabla" to "∇", "circ" to "∘", "ast" to "∗",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "vartheta" to "θ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ",
        "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ",
        "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
        "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
        "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "left" to "", "right" to "", "displaystyle" to "", "limits" to "",
        "big" to "", "Big" to "", "bigg" to "", "Bigg" to "",
        "bigl" to "", "bigr" to "", "Bigl" to "", "Bigr" to "",
        // 单字符命令（反斜杠 + 一个非字母字符）。
        "%" to "%", "$" to "$", "&" to "&", "#" to "#", "_" to "_",
        "{" to "{", "}" to "}", "," to " ", ";" to " ", ":" to " ",
        "!" to "", " " to " ", "|" to "∥", "\\" to " ",
    )
}
