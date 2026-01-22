# Sound Detection System

## How Detection Works

The sound detection system uses real-time audio analysis to detect sudden, loud sounds like claps or knocks.

### Detection Algorithm

1. **Audio Sampling**: Records audio at 16 kHz using the device microphone
2. **Window Analysis**: Processes audio in 100ms windows (~1600 samples per window)
3. **RMS Calculation**: Computes Root Mean Square (RMS) amplitude for each window
4. **Peak Detection**: Identifies the maximum amplitude within each window
5. **Adaptive Threshold**: Maintains a dynamic noise floor that adapts to ambient sound
6. **Detection Criteria**: A sound is detected when:
   - RMS amplitude > (noise floor × 3.5)
   - RMS amplitude > 0.02 (minimum absolute threshold)
   - Peak-to-RMS ratio ≥ 2.0 (ensures sudden, sharp sounds)

### Key Parameters

- **Sample Rate**: 16,000 Hz
- **Window Size**: 100ms (1600 samples)
- **Noise Floor Alpha**: 0.05 (learning rate for adaptive threshold)
- **Threshold Multiplier**: 3.5× above noise floor
- **Minimum RMS**: 0.02 (prevents false triggers on silence)
- **Peak Ratio**: 2.0 (peak must be 2× the RMS for detection)

### Waveform Capture

Each detection event captures:
- **Timestamp**: System time in milliseconds (using `SystemClock.elapsedRealtime()`)
- **Strength**: The RMS amplitude at detection
- **Peak**: The maximum amplitude at detection
- **Samples**: Up to 500ms of RMS history (50 samples at 10ms intervals)

## Soundwave Visualization Screen

The soundwave screen provides tools to analyze and refine detection performance.

### Main Components

#### 1. Status Indicators
- **Detection Light** (Red): Flashes when a sound is detected
- **Listening Light** (Orange/Yellow): Shows real-time audio input level
- **Statistics**: Current RMS, noise floor, and total detection count

#### 2. Detection Events Over Time Graph
Shows all detected events in a timeline:
- **X-axis**: Time in milliseconds (relative to first detection)
- **Y-axis**: Decibel (dB) levels
- **Blue Line**: Connects strength values across detections
- **Orange Dots**: Peak values for each detection
- **Red Line**: Noise floor threshold
- **Grid**: Reference lines for easy reading

**Purpose**: Visualize when detections occur and their relative intensity to identify patterns or false triggers.

#### 3. Detection Detail View (Click on any detection)
Shows the captured waveform for a single detection event:
- **Waveform Graph**: RMS values over ~500ms capture window
  - Each point represents a 10ms sample
  - Blue line connects the samples
  - Shows the actual sound envelope leading up to and following the detection
- **Reference Lines**:
  - **Peak** (solid): The maximum detected amplitude
  - **Strength** (semi-transparent): The RMS amplitude at detection
  - **Noise** (red): The current noise floor
- **Legend**: Color-coded indicators for each line type

**Purpose**: Examine individual detection waveforms to:
- Verify the sound pattern matches expectations (sharp peak for claps)
- Check if noise floor is set appropriately
- Identify why certain sounds were or weren't detected

#### 4. Recent Detections List
Clickable cards showing:
- Timestamp and age
- Raw strength and peak values
- dB conversions
- Peak/Strength ratio (useful for tuning the 2.0 threshold)

### Using the Screen to Refine Detection

1. **Test Your Sounds**: Clap or knock near the device while viewing the screen
2. **Check Detection Light**: Verify it flashes for your intended sounds
3. **Review Timeline**: Look for unwanted detections (false positives) or missed sounds (false negatives)
4. **Examine Waveforms**: Click detections to see if they have the expected sharp peak pattern
5. **Analyze Ratios**: Check Peak/Strength ratios - true claps should be ≥2.0
6. **Monitor Noise Floor**: Ensure it's stable and not too high (causing missed detections) or too low (causing false triggers)

### Understanding dB Values

The screen converts RMS amplitude to decibels using:
```
dB = 20 × log₁₀(RMS)
```

Typical ranges:
- **Silence**: < -40 dB
- **Ambient noise**: -30 to -20 dB  
- **Clap/Knock**: -10 to 0 dB or higher
- **Noise floor**: Usually -35 to -25 dB depending on environment

### Tips for Best Results

- **Quiet Environment**: Test in a room with minimal background noise
- **Consistent Sounds**: Make similar claps/knocks to verify consistent detection
- **Distance**: Test from your typical usage distance (e.g., arm's length from device)
- **Review History**: Look for patterns in successful vs failed detections
- **Adjust Expectations**: Very soft sounds or continuous noise won't trigger detection by design
