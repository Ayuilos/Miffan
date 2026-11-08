package me.ayuilos.miffan.utils

/**
 * 移除字符串中的Markdown格式
 * @return 移除Markdown格式后的纯文本
 */
fun String.stripMarkdown(): String {
    // The patterns are compiled once: previews are built for every quote and list row, often per frame.
    return this
        // 移除代码块；行内代码只去掉反引号，保留内容（文件名、命令在预览里要看得到）
        .replace(StripMarkdown.codeBlock, "")
        .replace(StripMarkdown.inlineCode, "$1")
        // 移除图片和链接，但保留其文本内容
        .replace(StripMarkdown.link, "$1")
        // 移除加粗和斜体 (先处理两个星号的)
        .replace(StripMarkdown.bold, "$1")
        .replace(StripMarkdown.italic, "$1")
        // 下划线强调只识别独立边界，保留 snake_case；单下划线不匹配双下划线的一部分。
        .replace(StripMarkdown.underlineBold, "$1")
        .replace(StripMarkdown.underlineItalic, "$1")
        // 移除删除线
        .replace(StripMarkdown.strike, "$1")
        // 移除标题标记 (多行模式)
        .replace(StripMarkdown.heading, "")
        // 移除列表标记 (多行模式)
        .replace(StripMarkdown.bullet, "")
        .replace(StripMarkdown.numbered, "")
        // 移除引用标记 (多行模式)
        .replace(StripMarkdown.quote, "")
        // 移除水平分割线
        .replace(StripMarkdown.rule, "")
        // 将多个换行符压缩，以保留段落
        .replace(StripMarkdown.blankLines, "\n\n")
        .trim()
}

private object StripMarkdown {
    val codeBlock = Regex("```[\\s\\S]*?```")
    val inlineCode = Regex("`([^`]*?)`")
    val link = Regex("!?\\[([^\\]]+)\\]\\([^\\)]*\\)")
    val bold = Regex("\\*\\*([^*]+?)\\*\\*")
    val italic = Regex("\\*([^*]+?)\\*")
    val underlineBold = Regex("(?<![\\p{L}\\p{N}_])__([^_]+?)__(?![\\p{L}\\p{N}_])")
    val underlineItalic = Regex("(?<![\\p{L}\\p{N}_])_([^_]+?)_(?![\\p{L}\\p{N}_])")
    val strike = Regex("~~([^~]+?)~~")
    val heading = Regex("(?m)^#+\\s*")
    val bullet = Regex("(?m)^\\s*[-*+]\\s+")
    val numbered = Regex("(?m)^\\s*\\d+\\.\\s+")
    val quote = Regex("(?m)^>\\s*")
    val rule = Regex("(?m)^(\\s*[-*_]){3,}\\s*$")
    val blankLines = Regex("\n{3,}")
}

fun String.extractThinkingTitle(): String? {
    // 按行分割文本
    val lines = this.lines()

    // 从后往前查找最后一个符合条件的加粗文本行
    for (i in lines.indices.reversed()) {
        val line = lines[i].trim()

        // 检查是否为加粗格式且独占一整行
        val boldPattern = Regex("^\\*\\*(.+?)\\*\\*$")
        val match = boldPattern.find(line)

        if (match != null) {
            // 返回加粗标记内的文本内容
            return match.groupValues[1].trim().takeUnless { it.isBlank() }
        }
    }

    return null
}
