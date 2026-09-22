package com.pogo.companion

/** One answer on the pill: a small caption naming the question and the answer under it. */
data class PillSlot(val caption: String, val value: String, val color: Int? = null)

/** What the floating pill displays after an evaluation. Text only, any number of answers. */
data class PillState(val mode: String, val target: String, val slots: List<PillSlot>) {
    companion object {
        /** A status message (no captions), e.g. "NOTHING TO READ". */
        fun message(mode: String, vararg lines: String) =
            PillState(mode, "", lines.map { PillSlot("", it) })
    }
}

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

    /** Last AUTO events (newest last), for the troubleshooting panel in My Log. */
    @Volatile var autoTrail: String = ""

    /** Set by the service: hides the pill while the full app is on screen. */
    @Volatile var pillVisibility: ((Boolean) -> Unit)? = null
}
