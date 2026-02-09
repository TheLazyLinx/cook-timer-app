package ro.maris.tools.cooktimer.ui.run

import androidx.compose.ui.graphics.Path

// Helper to create a skinny hexagon (pointed double-wedge) shape
// w: width, h: height of bounding box
// pointFactor: 0.0 to 0.5. Determines how far the shoulder starts from top/bottom.
// For 45 deg point if w < h: shoulderY = w/2. factor = (w/2)/h.
fun createHexagonPath(w: Float, h: Float): Path {
    val cx = w / 2
    val cy = h / 2
    
    // Calculate point offset based on width to get approx 45-60 degree tip
    // If we want "skinny", maybe the tip height is equal to half width.
    val tipH = w / 2 
    
    // Ensure we don't cross over if h is very small
    val safeTipH = tipH.coerceAtMost(h / 2)
    
    return Path().apply {
        moveTo(cx, 0f) // Top Tip
        lineTo(w, safeTipH) // Top Right
        lineTo(w, h - safeTipH) // Bottom Right
        lineTo(cx, h) // Bottom Tip
        lineTo(0f, h - safeTipH) // Bottom Left
        lineTo(0f, safeTipH) // Top Left
        close()
    }
}
