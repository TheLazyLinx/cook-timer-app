package ro.maris.tools.cooktimer.ui.run

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

fun Modifier.diamondBorder(
    color: Color,
    diamondCount: Int = 30, // Default will be overridden
    diamondSizeFraction: Float = 0.15f // Fraction of radius
): Modifier = this.drawWithContent {
    drawContent()
    
    val center = Offset(size.width / 2, size.height / 2)
    val maxDimension = min(size.width, size.height)
    val radius = maxDimension / 2
    
    // User request: "twice as long"
    val adjustedLen = radius * diamondSizeFraction * 2f
    
    // User request: "twice as dense" -> double the count passed if it was the old default, 
    // but better to just let the caller decide or use a higher default or calculation.
    // Actually, "twice as dense" means angleStep is smaller.
    // "half as thick" means width is smaller.
    
    val effectiveCount = diamondCount * 2
    val angleStep = 360f / effectiveCount
    
    // Inner radius for calculations
    val innerRadius = radius - adjustedLen
    
    // User request: "half as thick" (width)
    // Old calculation: (2 * PI * r / count * 0.6)
    // New calculation: half of that relative proportion, or since density doubled, the available space halved anyway.
    // So if I keep 0.6 (60% fill) with double density, they stay proportionally same "thickness" relative to gap.
    // But user said "half as thick", effectively making them thinner needles.
    // Let's use 0.3 instead of 0.6
    val diamondWidth = (2 * Math.PI * innerRadius / effectiveCount * 0.3).toFloat()
    
    for (i in 0 until effectiveCount) {
        val angle = -90f + (i * angleStep)
        
        rotate(degrees = angle, pivot = center) {
             val topY = center.y - radius
             val midY = topY + adjustedLen / 2
             val botY = topY + adjustedLen
             val midX = center.x
             val halfW = diamondWidth / 2
             
             val path = Path().apply {
                 moveTo(midX, topY)
                 lineTo(midX + halfW, midY)
                 lineTo(midX, botY)
                 lineTo(midX - halfW, midY)
                 close()
             }
             drawPath(path, color)
        }
    }
}
