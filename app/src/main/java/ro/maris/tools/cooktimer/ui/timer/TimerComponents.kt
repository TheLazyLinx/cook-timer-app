package ro.maris.tools.cooktimer.ui.timer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun DurationPicker(
    durationSec: Int,
    onDurationChange: (Int) -> Unit
) {
    val hrs = durationSec / 3600
    val mins = (durationSec % 3600) / 60
    val secs = durationSec % 60

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TimeUnitColumn(
            value = hrs,
            onValueChange = { newHrs ->
                val newDuration = (newHrs * 3600) + (mins * 60) + secs
                onDurationChange(newDuration.coerceAtLeast(0))
            },
            label = "h",
            range = 0..99
        )
        Text(
            text = ":",
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        TimeUnitColumn(
            value = mins,
            onValueChange = { newMins ->
                val newDuration = (hrs * 3600) + (newMins * 60) + secs
                onDurationChange(newDuration.coerceAtLeast(0))
            },
            label = "m",
            range = 0..59
        )
        Text(
            text = ":",
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        TimeUnitColumn(
            value = secs,
            onValueChange = { newSecs ->
                val newDuration = (hrs * 3600) + (mins * 60) + newSecs
                onDurationChange(newDuration.coerceAtLeast(0))
            },
            label = "s",
            range = 0..59
        )
    }
}

@Composable
private fun TimeUnitColumn(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    range: IntRange
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.width(60.dp)
    ) {
        IconButton(onClick = {
            val next = if (value + 1 > range.last) range.first else value + 1
            onValueChange(next)
        }) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Increase")
        }
        
        Text(
            text = "%02d".format(value),
            style = MaterialTheme.typography.displayMedium
        )
        
        IconButton(onClick = {
            val prev = if (value - 1 < range.first) range.last else value - 1
            onValueChange(prev)
        }) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Decrease")
        }
        
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
