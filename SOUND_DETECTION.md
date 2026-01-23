# Sound Detection System (Architecture 2.0)

## Overview

The detection system has evolved from a simple loudness trigger into a sophisticated **Confidence-Based Classifier**. Instead of asking *"Is it loud?"*, it now asks *"How likely is this sound a Snap or Slap versus background noise or speech?"*.

It uses **Spectral Analysis** (FFT) to examine the frequency content of every sound in real-time, filtering out "impostors" like dropping objects, human speech, and frying pan crackles.

---

## 1. The Detection Pipeline

### Step A: The Adaptive Noise Gate (The Bouncer)
Before any analysis happens, the sound must prove it is distinct from the background.
*   **Acoustic Echo Cancellation (AEC)**: Filters out audio playing from the device itself (alarms, timers, music) so the app doesn't trigger on its own sounds.
*   **Dynamic Threshold**: `Threshold = NoiseFloor * 2.8`.
*   **Asymmetric Adaptation**: 
    *   **Rise (Slow)**: Ignores speech/music so they don't artificially raise the floor.
    *   **Fall (Moderate)**: Recovers sensitivity during pauses in conversation without "crashing" the floor.
*   **Anti-Latch**: If a sound stays loud for >2.0 seconds (e.g., turning on a faucet/blender), the system keeps the threshold naturally high to ignore it.
*   **Warm-Up Phase**: Upon start, the system adapts 50x faster for 0.5s to instantly calibrate to the room.

### Step B: Event Isolation
Once a sound crosses the threshold, we check its context.
*   **100ms Lookback**: We analyze the 100ms *before* the trigger.
*   **Rejection**: If the "Pre-Event" noise was chaotic (high variance), the trigger is rejected. This filters out "Cascading Noises" (like frying food or shuffling papers) where the "loudest pop" is just part of a clutter.

### Step C: Spectral Analysis (The Fingerprint)
The system calculates key acoustic metrics for the isolated sound:
1.  **Duration**: How long did it stay loud? (Snaps < 100ms, Speech > 200ms).
2.  **Centroid (Hz)**: The "Center of Gravity" of the frequency. (Snaps are High pitch, Knocks are Low pitch).
3.  **ZCR (Zero Crossing Rate)**: How "hissy" or sharp is the sound?
4.  **Spectral Tilt (Slope)**: *New in 2.0*. 
    *   **Speech/Music**: Negative Tilt (Bass heavy, rolls off highs).
    *   **Snaps/Slaps**: Positive/Flat Tilt (Energy is crisp across the spectrum).

### Step D: Confidence Scoring
The sound is compared against multiple **Profiles**. A score (0% to 100%) is calculated based on how close the sound is to the profile's "Ideal Center" for each metric.

**The Trigger logic:**
*   **Score > 60%**: Valid Match.
*   **Winner Take All**: The profile with the highest score wins.

---

## 2. Recognized Profiles

### 🫰 Snaps (The Precision Triggers)
Recognizes both crisp snaps and duller "thud" snaps.
*   **Snap (High)**: >4000Hz. Very crisp. Rejects almost all false positives.
*   **Snap (Mid)**: 1000-4000Hz. Standard snap.
*   **Snap (Low)**: 300-1000Hz. Duller snaps. Tightly gated to avoid matching table bumps.

### ✋ Slaps (The "Wet" Triggers)
Designed to handle the variety of hand-to-skin or hand-to-surface sounds.
*   **Slap (Standard)**: 1000-5000Hz. Balanced slap.
*   **Slap (Wet)**: 150-1500Hz. Heavy, tonal thud. Allows deeper bass (Tilt -30).
*   **Slap (Airy)**: 3000-10000Hz. Light, glancing contact. High ZCR.

### 🚪 Knocks
*   **Knock**: 50-3000Hz. Very short duration, very low ZCR.

---

## 3. False Positive Rejection Strategies

| Issue | Strategy |
| :--- | :--- |
| **Speech** | **Spectral Tilt Filter**. Speech has a localized frequency slope. Snaps are broadband impulses. |
| **Frying Pan** | **100ms Lookback**. "Crackles" are rarely isolated. If the pre-history is noisy, the event is ignored. |
| **Blenders** | **Anti-Latch**. Continuous loud noise forces the threshold up after 2 seconds. |
| **Drifting Noise** | **Asymmetric Gate**. The noise floor drops slowly enough to bridge gaps between words/beats. |
| **Soft Snaps** | **Dynamic Sensitivity**. In quiet rooms (< 0.0025 RMS), thresholds automatically lower to catch faint sounds. |

---

## 4. Debugging & Tuning

### The Log Format
Use Logcat filtered by `tag:SoundAnalysis` to see the decision matrix:
`Type, Confidence, Duration, RMS, Peak, ZCR, Centroid, Tilt, Isolation Score`

### Tuning Guide
*   **If "Rejected: Isolation"**: The environment is too cluttered.
*   **If "Rejected: Low Confidence"**: Check which metric failed.
    *   *Duration too high?* -> Likely handling noise.
    *   *Tilt too negative?* -> Likely speech or a dull object impact.

## Soundwave Visualization Screen
(Legacy components updated for new architecture)
- **Status**: Now shows "Confidence %" instead of just Pass/Fail.
- **Waveform**: Helpful for seeing the Isolation Ratio (Is the flat line before the spike truly flat?).
- **Clap/Knock**: -10 to 0 dB or higher
- **Noise floor**: Usually -35 to -25 dB depending on environment

### Tips for Best Results

- **Quiet Environment**: Test in a room with minimal background noise
- **Consistent Sounds**: Make similar claps/knocks to verify consistent detection
- **Distance**: Test from your typical usage distance (e.g., arm's length from device)
- **Review History**: Look for patterns in successful vs failed detections
- **Adjust Expectations**: Very soft sounds or continuous noise won't trigger detection by design
