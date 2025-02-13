data class BackupStats(
    var totalFiles: Int = 0,
    var copiedFiles: Int = 0,
    var skippedFiles: Int = 0,
    var failedFiles: Int = 0,
    var totalBytes: Long = 0,
    var startTime: Long = System.currentTimeMillis()
) {
    val elapsedTimeSeconds: Long
        get() = (System.currentTimeMillis() - startTime) / 1000

    override fun toString(): String {
        return """
            Backup Summary:
            - Total files processed: $totalFiles
            - Successfully copied: $copiedFiles
            - Skipped (unsupported): $skippedFiles
            - Failed to copy: $failedFiles
            - Total data copied: ${formatBytes(totalBytes)}
            - Time taken: ${elapsedTimeSeconds}s
        """.trimIndent()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }
}
