package com.example.data.download

sealed class DownloadResult {
    object Success : DownloadResult()
    data class RateLimited(val message: String, val retryAfterMs: Long? = null) : DownloadResult()
    data class Network(val message: String, val cause: Throwable? = null) : DownloadResult()
    data class Parse(val message: String, val cause: Throwable? = null) : DownloadResult()
    data class SourceUnavailable(val sourceId: String) : DownloadResult()
    data class StorageFull(val availableBytes: Long, val requiredBytes: Long) : DownloadResult()
    data class StorageUnavailable(val location: String, val message: String) : DownloadResult()
    data class ChapterMissing(val novelId: String, val chapterId: String) : DownloadResult()

    val isSuccess: Boolean get() = this is Success
}
