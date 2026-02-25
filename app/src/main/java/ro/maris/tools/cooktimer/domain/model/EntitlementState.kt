package ro.maris.tools.cooktimer.domain.model

enum class EntitlementSource {
    CACHE,
    PURCHASES,
    NONE
}

data class EntitlementState(
    val isPremium: Boolean,
    val source: EntitlementSource
)
