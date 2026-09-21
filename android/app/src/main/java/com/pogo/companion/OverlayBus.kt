package com.pogo.companion

/**
 * What the floating pill displays after an evaluation. Text only: each slot is a caption
 * naming the question ("VERDICT") and the answer under it ("TRADE").
 */
data class PillState(
    val mode: String,
    val target: String,
    val caption1: String, val value1: String,
    val caption2: String, val value2: String,
    val caption3: String, val value3: String
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
