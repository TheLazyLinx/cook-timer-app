package ro.maris.tools.cooktimer.domain.model

data class Preset(
    val id: String,
    val name: String,
    val steps: List<Step>,
    val isBuiltIn: Boolean
)

