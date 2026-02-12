package org.xross.helper

internal object StringHelper {
    /**
     * スネークケースをキャメルケースに変換します。
     * 例: "my_service_name" -> "myServiceName"
     */
    fun String.toCamelCase(): String {
        if (this.isEmpty()) return ""
        val parts = this.split("_")
        val first = parts[0]
        val others = parts.drop(1).joinToString("") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        return first + others
    }

    /**
     * Kotlinの予約語と衝突する場合にバッククォートでエスケープします。
     */
    fun String.escapeKotlinKeyword(): String = if (this in KOTLIN_KEYWORDS) "`$this`" else this

    private val KOTLIN_KEYWORDS = setOf(
        "package", "as", "typealias", "class", "this", "super",
        "val", "var", "fun", "for", "is", "in", "throw", "return",
        "break", "continue", "object", "if", "else", "while",
        "do", "try", "when", "interface", "typeof",
    )
}
