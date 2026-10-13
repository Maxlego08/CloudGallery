package fr.eva.cloudgallery.domain.model

/**
 * Estimate before "Cache all of OneDrive": items concerned (thumbnail and preview coming from
 * OneDrive), items not cached yet, estimated size of what remains to download and free space
 * on the device. [enoughSpace]: the estimate fits while keeping the minimum reserved space
 * (otherwise the pass will stop before the end).
 */
data class PrecacheEstimate(
    val total: Int,
    val missing: Int,
    val estimatedBytes: Long,
    val freeBytes: Long,
    val enoughSpace: Boolean,
)

/** Outcome of the last caching pass. */
enum class PrecacheOutcome {
    /** Everything is cached (except items without a thumbnail). */
    COMPLETED,

    /** Stopped to keep free space on the device. */
    LOW_SPACE,

    /** OneDrive account signed out or needing re-sign-in. */
    SIGNED_OUT,
}

/** State of the caching of all of OneDrive, as seen by the Storage page. */
sealed interface PrecacheState {
    /** Nothing in progress (never started, or cancelled). */
    data object Idle : PrecacheState

    /** Waiting for conditions (Wi-Fi, battery) or for a resume after a network outage. */
    data object Waiting : PrecacheState

    /** In progress: [done] items processed out of [total], [bytes] downloaded during this pass. */
    data class Running(val done: Int, val total: Int, val bytes: Long, val failed: Int) : PrecacheState

    /** Last pass finished. [failed]: items without a thumbnail or that failed. */
    data class Finished(val outcome: PrecacheOutcome, val done: Int, val total: Int, val failed: Int) : PrecacheState
}
