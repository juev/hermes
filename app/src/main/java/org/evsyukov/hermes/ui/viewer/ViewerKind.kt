package org.evsyukov.hermes.ui.viewer

/** How the app shows a file itself, without handing it to another app. */
enum class ViewerKind { IMAGE, PDF, MARKDOWN, TEXT, AUDIO, VIDEO, OTHER }

private val TEXT_EXTENSIONS = setOf(
    "txt", "log", "csv", "tsv", "json", "jsonl", "yaml", "yml", "toml", "ini", "conf", "cfg", "env", "xml",
    "html", "htm", "css", "js", "ts", "tsx", "jsx", "kt", "kts", "java", "go", "py", "rb", "rs", "c", "h",
    "cpp", "hpp", "cs", "swift", "sh", "bash", "zsh", "fish", "sql", "diff", "patch", "gradle", "properties",
    "lua", "php", "pl", "r", "scala", "dart", "vue", "svelte", "dockerfile", "makefile", "srt", "vtt",
)
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "ico")
private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "ogg", "oga", "opus", "wav", "flac", "aac", "weba")
private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "3gp", "m4v")

/** Picks the viewer by the MIME type and falls back to the extension, which servers often know better. */
fun viewerKind(name: String, mime: String?): ViewerKind {
    val type = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    val ext = name.substringAfterLast('.', "").lowercase()
    val base = name.substringAfterLast('/').lowercase()
    return when {
        ext == "md" || ext == "markdown" || type == "text/markdown" -> ViewerKind.MARKDOWN
        ext == "pdf" || type == "application/pdf" -> ViewerKind.PDF
        type.startsWith("image/") || ext in IMAGE_EXTENSIONS -> ViewerKind.IMAGE
        type.startsWith("audio/") || ext in AUDIO_EXTENSIONS -> ViewerKind.AUDIO
        type.startsWith("video/") || ext in VIDEO_EXTENSIONS -> ViewerKind.VIDEO
        type.startsWith("text/") || ext in TEXT_EXTENSIONS || base == "dockerfile" || base == "makefile" -> ViewerKind.TEXT
        type == "application/json" || type == "application/xml" || type.endsWith("+json") || type.endsWith("+xml") -> ViewerKind.TEXT
        else -> ViewerKind.OTHER
    }
}

/** The language name for code highlighting of a text file, or null for plain text. */
fun codeLanguage(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> "kotlin"
    "py" -> "python"
    "js", "jsx" -> "javascript"
    "ts", "tsx" -> "typescript"
    "go" -> "go"
    "rs" -> "rust"
    "java" -> "java"
    "c", "h" -> "c"
    "cpp", "hpp" -> "cpp"
    "cs" -> "csharp"
    "swift" -> "swift"
    "rb" -> "ruby"
    "php" -> "php"
    "sh", "bash", "zsh" -> "shell"
    "json", "jsonl" -> "json"
    "yaml", "yml" -> "yaml"
    "sql" -> "sql"
    "dart" -> "dart"
    "pl" -> "perl"
    else -> null
}
