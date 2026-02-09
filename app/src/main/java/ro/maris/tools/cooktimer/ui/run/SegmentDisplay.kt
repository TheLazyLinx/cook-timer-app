package ro.maris.tools.cooktimer.ui.run

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun SixteenSegmentDisplay(
    text: String,
    modifier: Modifier = Modifier,
    segmentColor: Color = Color.Red,
    dimmedColor: Color = segmentColor.copy(alpha = 0.1f),
    spacing: Dp = 4.dp
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        text.forEach { char ->
            SegmentChar(
                char = char,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .aspectRatio(0.55f), // Slightly taller for better digit look
                activeColor = segmentColor,
                inactiveColor = dimmedColor
            )
        }
    }
}

@Composable
fun SegmentChar(
    char: Char,
    modifier: Modifier = Modifier,
    activeColor: Color,
    inactiveColor: Color
) {
    val segments = remember(char) { getSegmentsForChar(char) }
    
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Make segments slightly thinner for crispness
        val sW = w * 0.10f 
        val gap = w * 0.03f 

        when (char) {
            ':' -> {
                // Draw colon dots square for retro feel
                val dotSize = sW * 1.5f
                drawRect(activeColor, topLeft = Offset(w/2 - dotSize/2, h/3 - dotSize/2), size = Size(dotSize, dotSize))
                drawRect(activeColor, topLeft = Offset(w/2 - dotSize/2, h*2/3 - dotSize/2), size = Size(dotSize, dotSize))
                return@Canvas
            }
            '.' -> {
                val dotSize = sW * 1.5f
                drawRect(activeColor, topLeft = Offset(w/2 - dotSize/2, h - sW - dotSize), size = Size(dotSize, dotSize))
                return@Canvas
            }
            ' ' -> {
                return@Canvas
            }
        }

        // Helper to draw segment
        fun drawSeg(id: Int, path: Path) {
            val isActive = (segments shr id) and 1 == 1
            drawPath(
                path = path,
                color = if (isActive) activeColor else inactiveColor,
                style = Fill
            )
        }
        
        val halfW = w / 2
        val halfH = h / 2
        
        // Revised Path Creation for Crisp Non-Overlapping Segments
        // We calculate coordinate points with explicit gaps.
        
        // Horizontals
        // A1 (Top Left)
        drawSeg(0, createPath(
            gap, 0f, 
            halfW - gap/2, 0f, 
            halfW - gap/2 - sW, sW, 
            gap + sW, sW
        ))
        // A2 (Top Right)
        drawSeg(1, createPath(
            halfW + gap/2, 0f, 
            w - gap, 0f, 
            w - gap - sW, sW, 
            halfW + gap/2 + sW, sW
        ))
        
        // Verticals - Upper
        // F (Top Left Vert)
        drawSeg(7, createPath(
            0f, gap, 
            sW, gap + sW, 
            sW, halfH - gap/2 - sW/2, 
            0f, halfH - gap/2
        ))
        // B (Top Right Vert)
        drawSeg(2, createPath(
            w - sW, gap + sW, 
            w, gap, 
            w, halfH - gap/2, 
            w - sW, halfH - gap/2 - sW/2
        ))
        // I (Top Middle Vert)
        drawSeg(11, createPath(
            halfW - sW/2, gap + sW/2,
            halfW + sW/2, gap + sW/2,
            halfW + sW/2, halfH - gap - sW/2,
            halfW - sW/2, halfH - gap - sW/2
        ))

        // Middle Horizontals
        // G1 (Mid Left)
        drawSeg(8, createPath(
            gap, halfH,
            gap + sW, halfH - sW/2,
            halfW - gap/2 - sW, halfH - sW/2,
            halfW - gap/2, halfH,
            halfW - gap/2 - sW, halfH + sW/2,
            gap + sW, halfH + sW/2
        ))
        // G2 (Mid Right)
        drawSeg(9, createPath(
            halfW + gap/2, halfH,
            halfW + gap/2 + sW, halfH - sW/2,
            w - gap - sW, halfH - sW/2,
            w - gap, halfH,
            w - gap - sW, halfH + sW/2,
            halfW + gap/2 + sW, halfH + sW/2
        ))

        // Verticals - Lower
        // E (Bot Left Vert)
        drawSeg(6, createPath(
            0f, halfH + gap/2,
            sW, halfH + gap/2 + sW/2,
            sW, h - gap - sW,
            0f, h - gap
        ))
        // C (Bot Right Vert)
        drawSeg(3, createPath(
            w - sW, halfH + gap/2 + sW/2,
            w, halfH + gap/2,
            w, h - gap,
            w - sW, h - gap - sW
        ))
        // L (Bot Middle Vert)
        drawSeg(14, createPath(
            halfW - sW/2, halfH + gap + sW/2,
            halfW + sW/2, halfH + gap + sW/2,
            halfW + sW/2, h - gap - sW/2,
            halfW - sW/2, h - gap - sW/2
        ))

        // Bottom Horizontals
        // D1 (Bot Left)
        drawSeg(5, createPath(
            gap + sW, h - sW,
            halfW - gap/2 - sW, h - sW,
            halfW - gap/2, h,
            gap, h
        ))
        // D2 (Bot Right)
        drawSeg(4, createPath(
            halfW + gap/2 + sW, h - sW,
            w - gap - sW, h - sW,
            w - gap, h,
            halfW + gap/2, h
        ))

        // Diagonals - Simplified as thin bars with gaps
        // H (TL Diag)
        drawSeg(10, createPath(
            gap + sW, gap + sW,
            gap + sW*2, gap + sW,
            halfW - gap - sW, halfH - gap - sW,
            halfW - gap - sW*2, halfH - gap - sW
        ))
        // J (TR Diag)
        drawSeg(12, createPath(
            w - gap - sW*2, gap + sW,
            w - gap - sW, gap + sW,
            halfW + gap + sW*2, halfH - gap - sW,
            halfW + gap + sW, halfH - gap - sW
        ))
        // K (BL Diag)
        drawSeg(13, createPath(
            halfW - gap - sW*2, halfH + gap + sW,
            halfW - gap - sW, halfH + gap + sW,
            gap + sW*2, h - gap - sW,
            gap + sW, h - gap - sW
        ))
        // M (BR Diag)
        drawSeg(15, createPath(
            halfW + gap + sW, halfH + gap + sW,
            halfW + gap + sW*2, halfH + gap + sW,
            w - gap - sW, h - gap - sW,
            w - gap - sW*2, h - gap - sW
        ))
    }
}

// Helper to create arbitrary polygon path from pairs of floats (x,y)
fun createPath(vararg points: Float): Path {
    return Path().apply {
        if (points.size >= 2) {
            moveTo(points[0], points[1])
            for (i in 2 until points.size step 2) {
                lineTo(points[i], points[i+1])
            }
            close()
        }
    }
}

// 16-segment mapping (Standard)
// This is a simplified mapping.
// 0: A1, 1: A2, 2: B, 3: C, 4: D2, 5: D1, 6: E, 7: F
// 8: G1, 9: G2, 10: H, 11: I, 12: J, 13: K, 14: L, 15: M

private fun getSegmentsForChar(c: Char): Int {
    return when (c.uppercaseChar()) {
        '0' -> 0b00000000_11111111 // A1,A2,B,C,D2,D1,E,F -> outer ring
                .or(1 shl 10).or(1 shl 15) // Slash zero sometimes? Or just outer. Let's do just outer for 0.
        '1' -> (1 shl 2) or (1 shl 3) // B, C
        '2' -> (1 shl 0) or (1 shl 1) or (1 shl 2) or (1 shl 9) or (1 shl 8) or (1 shl 6) or (1 shl 5) or (1 shl 4)
        '3' -> (1 shl 0) or (1 shl 1) or (1 shl 2) or (1 shl 3) or (1 shl 4) or (1 shl 5) or (1 shl 8) or (1 shl 9)
        '4' -> (1 shl 7) or (1 shl 8) or (1 shl 9) or (1 shl 2) or (1 shl 3)
        '5' -> (1 shl 0) or (1 shl 1) or (1 shl 7) or (1 shl 8) or (1 shl 9) or (1 shl 3) or (1 shl 4) or (1 shl 5)
        '6' -> (1 shl 0) or (1 shl 1) or (1 shl 7) or (1 shl 6) or (1 shl 5) or (1 shl 4) or (1 shl 3) or (1 shl 8) or (1 shl 9)
        '7' -> (1 shl 0) or (1 shl 1) or (1 shl 2) or (1 shl 3)
        '8' -> 0b00000011_11111111 // All outer + middle
        '9' -> (1 shl 0) or (1 shl 1) or (1 shl 7) or (1 shl 2) or (1 shl 8) or (1 shl 9) or (1 shl 3) or (1 shl 4) or (1 shl 5)
        ':' -> 0 // Handle separately if needed or just empty
        // Add more as needed. Default loop all on for debug?
        else -> 0
    }
}

@Composable
fun CircularSegmentedProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    totalSegments: Int = 60,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = activeColor.copy(alpha = 0.2f),
    content: @Composable () -> Unit
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // The Progress Ring
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = minOf(size.width, size.height) / 2
            val segmentLength = radius * 0.1f // Length of dash
            val innerRadius = radius - segmentLength
            
            val angleStep = 360f / totalSegments
            val tickWidth = (2 * Math.PI * innerRadius / totalSegments * 0.6).toFloat() // 60% fill width
            
            for (i in 0 until totalSegments) {
                // Timer usually goes clockwise from top (270 degrees in standard math, or -90)
                // Let's start from top (-90)
                val angle = -90f + (i * angleStep)
                val isActive = (i.toFloat() / totalSegments) < progress
                
                // Rotate canvas to draw simple rect at top
                rotate(degrees = angle, pivot = center) {
                    val isQuarter = (i % (totalSegments / 4) == 0)
                    val tickLen = if (isQuarter) segmentLength * 1.3f else segmentLength
                    
                    // Quarter markers are always the accent color, but perhaps slightly dimmed if not active
                    // Standard ticks are inactiveColor unless active
                    val color = if (isActive) {
                        activeColor
                    } else if (isQuarter) {
                        activeColor.copy(alpha = 0.5f)
                    } else {
                        inactiveColor
                    }
                    
                    // Diamond shape for ticks
                    val path = Path().apply {
                         // Centered at top of circle (approx)
                         // Width tickWidth, Height tickLen
                         // To make diamond:
                         // Top: (0, -radius)
                         // Right: (width/2, -radius + len/2)
                         // Bottom: (0, -radius + len)
                         // Left: (-width/2, -radius + len/2)
                         
                         val topY = center.y - radius
                         val midY = topY + tickLen / 2
                         val botY = topY + tickLen
                         val midX = center.x
                         val halfW = tickWidth / 2
                         
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
        
        // Inner Content
        Box(modifier = Modifier.padding(32.dp), contentAlignment = Alignment.Center) {
            content()
        }
    }
}
