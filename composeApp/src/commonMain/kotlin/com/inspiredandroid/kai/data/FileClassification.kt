package com.inspiredandroid.kai.data

enum class FileCategory {
    IMAGE,
    TEXT,
    PDF,
    /** Anything that can't be sent to the model directly lives in the workspace instead. */
    BINARY,
    UNSUPPORTED,
}

const val MAX_TEXT_FILE_BYTES = 200_000
const val MAX_PDF_BYTES = 20_000_000
const val MAX_IMAGE_BYTES = 15_000_000

// Raw image input cap before compression — images typically shrink after compression,
// so we allow larger raw files than MAX_IMAGE_BYTES while still preventing an OOM
// from reading a multi-gigabyte file into memory.
const val MAX_RAW_IMAGE_BYTES = 50_000_000

/** Binary workspace uploads (.kt/.zip/.apk/.so/…) land in /root/chat-uploads. */
const val MAX_BINARY_FILE_BYTES = 200_000_000

private val textMimeTypes = setOf(
    "application/json",
    "application/xml",
    "application/javascript",
    "application/x-yaml",
    "application/yaml",
    "application/x-sh",
    "application/sql",
    "application/graphql",
    "application/toml",
)

private val textExtensions = setOf(
    "txt", "md", "json", "csv", "xml", "yaml", "yml",
    "html", "css", "js", "ts", "kt", "kts", "java",
    "py", "rb", "rs", "go", "c", "h", "cpp", "hpp",
    "swift", "sh", "bash", "zsh", "sql", "graphql",
    "toml", "ini", "cfg", "conf", "log", "properties",
    "gradle", "tsx", "jsx", "gsc",
)

internal val imageExtensions = setOf(
    "jpg",
    "jpeg",
    "png",
    "gif",
    "webp",
    "bmp",
    "svg",
)

val binaryExtensions = setOf(
    "zip", "apk", "aab", "apks", "apkm", "xapk", "jar", "dex", "so", "arsc",
    "ipa", "7z", "tar", "gz", "tgz", "bz2", "xz", "rar", "bin", "o", "a",
    "keystore", "jks", "pem", "crt", "der", "odex",
    "vdex", "art", "axml", "db", "sqlite", "exe", "dll", "dylib",
)

val supportedFileExtensions = (imageExtensions + textExtensions + binaryExtensions).toList()


fun classifyFile(mimeType: String?, fileName: String?): FileCategory {
    if (mimeType != null) {
        if (mimeType.startsWith("image/")) return FileCategory.IMAGE
        if (mimeType == "application/pdf") return FileCategory.PDF
        if (mimeType.startsWith("text/") || mimeType in textMimeTypes) return FileCategory.TEXT
    }
    // Fall back to extension
    val ext = fileName?.substringAfterLast('.', "")?.lowercase()
    if (ext != null && ext in imageExtensions) return FileCategory.IMAGE
    if (ext != null && ext in textExtensions) return FileCategory.TEXT
    if (ext == "pdf") return FileCategory.PDF

    // Everything else — zip, apk, aab, apks, jar, dex, so, keystores, whatever — goes to
    // the sandbox workspace so the agent can read/edit it there instead of being dropped.
    return FileCategory.BINARY
}
