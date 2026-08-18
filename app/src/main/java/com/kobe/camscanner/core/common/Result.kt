package com.kobe.camscanner.core.common

/**
 * A narrow result type used at the boundary of the engines (scanner, OCR, PDF).
 *
 * The failure side carries a *user-facing* message chosen from the strings in SDS 49, because the
 * rule for this app is that no crash and no raw exception text ever reaches the screen.
 */
sealed interface KobeResult<out T> {
    data class Success<T>(val value: T) : KobeResult<T>
    data class Failure(val reason: FailureReason, val cause: Throwable? = null) : KobeResult<Nothing>

    val valueOrNull: T? get() = (this as? Success)?.value
    val isSuccess: Boolean get() = this is Success
}

enum class FailureReason {
    CAMERA_UNAVAILABLE,
    NO_DOCUMENT_DETECTED,
    OCR_FAILED,
    PDF_FAILED,
    STORAGE_FAILED,
    IMPORT_FAILED,
    UNKNOWN,
}

inline fun <T> runCatchingKobe(
    reason: FailureReason,
    block: () -> T,
): KobeResult<T> = try {
    KobeResult.Success(block())
} catch (t: Throwable) {
    if (t is kotlinx.coroutines.CancellationException) throw t
    KobeResult.Failure(reason, t)
}
