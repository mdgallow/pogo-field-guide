package com.pogo.companion

/** What the floating pill displays after an evaluation. */
data class PillState(
    val mode: String,
    val target: String,
    val berryIcon: String, val berryLabel: String,
    val catchIcon: String, val catchLabel: String,
    val actionIcon: String, val actionLabel: String
)

/**
 * In-process link between MainActivity (owns the WebView + Pokédex data) and
 * FloatingOverlayService (owns the pill + screen capture). Both live in the same
 * process, so plain callbacks avoid Intent round-trips. All callbacks are invoked
 * on the main thread.
 */
object OverlayBus {
    /** Set by MainActivity: receives the OCR payload JSON and evaluates it in the WebView. */
    @Volatile var ocrEvaluator: ((String) -> Unit)? = null

    /** Set by the service: pushes evaluated slot data onto the pill. */
    @Volatile var pillUpdater: ((PillState) -> Unit)? = null

    /** Set by the service: hides the pill while the full app is on screen. */
    @Volatile var pillVisibility: ((Boolean) -> Unit)? = null
}
