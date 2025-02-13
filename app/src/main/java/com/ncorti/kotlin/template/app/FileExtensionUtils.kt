object FileExtensionUtils {
    // Document extensions
    private val DOCUMENT_EXTENSIONS = setOf(
        "pdf", "doc", "docx", "txt", "rtf", "odt",
        "xls", "xlsx", "csv", "ods",
        "ppt", "pptx", "odp"
    )

    // Image extensions
    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "bmp",
        "webp", "heic", "raw", "svg"
    )

    // All supported extensions
    val SUPPORTED_EXTENSIONS = DOCUMENT_EXTENSIONS + IMAGE_EXTENSIONS

    fun isSupported(fileName: String): Boolean {
        return fileName.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS
    }
}
