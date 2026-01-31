# Sound Detection System (Architecture 3.2)

## Overview

The detection system is a **Physics-Based Classifier**. It does not just listen for generic "loud noises". It specifically hunts for the acoustic signature of **Kinetic Impacts**—the physics of two surfaces striking each other (skin-on-skin, knuckle-on-wood).

It uses **Spectral Analysis** (FFT) combined with **Temporal Flux Analysis** to distinguish these sharp, transient impacts from the "impostors" of daily life (speech, groans, dropped items, shuffling). It also includes **Hardware Artifact Rejection** to handle audio driver glitches.

---

## 1. The Detection Pipeline

### Step A: High-Pass Filter (The Mud Guard)
Before volume is even measured, the signal passes through a **2000Hz High-Pass Filter**. 
*   **Rejects**: Vowels, deep voices, door thuds, appliance hums.
*   **Accepts**: The sharp "crack" of a snap or slap.

### Step B: The Flux Gate (Trigger)
A sound only triggers the system if:
1.  **HF Energy**: It has significant energy *above* 2000Hz.
2.  **Attack**: The volume rises explosively (2x increase) in < 10ms.
3.  **Isolation**: The 120ms *before* the sound was relatively quiet. (Rejects clicks inside a sentence).
4.  **Sanity Check**: The pre-event noise level must NOT be absolute zero (Digital Silence). This rejects "Wake-Up Pops" caused by microphone driver restarts.

*Success*: A valid trigger produces a "Clock Tick" haptic feedback response.

### Step C: Temporal Shape Checks (The Physics Check)
Once triggered, the system monitors the sound's evolution over time to ensure it behaves like an Impact.
*   **Sustain Check**: At 60ms, the sound must have decayed by >50%. (Rejects Speech/Screams which sustain energy).
*   **Early Peak**: The loudest moment must be in the first 60ms. (Rejects "Swells" or "Groans" that ramp up slowly).
*   **Duration Cap**: Sounds > 200ms are rejected. (Rejects dragging/sliding noises).

### Step D: Chaos Rejection (Anti-Rattle)
Random chaotic noise (pill bottles, keys jangling) can statistically align with a profile by chance. Two checks prevent this:
1.  **Pre-Event Variance**: The 300ms *before* the event is analyzed for coefficient of variation (CV). If CV > 0.15, the signal was already chaotic - reject.
2.  **Post-Event Quiet**: After the event ends, we wait 50ms and verify the signal has returned to quiet (<50% of threshold). If still noisy, it was part of ongoing chaos - reject.

### Step E: Spectral Profiling (The Fingerprint)
The isolated impact is analyzed for:
1.  **Centroid (Center of Gravity)**: Must be > 800Hz. (Deep sounds are ignored).
2.  **ZCR (Texture)**: Must be "noisy" enough (friction), but not *too* noisy (shushing).
3.  **Tilt (Shape)**: Must be relatively flat. (Speech has a steep bass-heavy tilt).

### Step F: Soft-Plateau Scoring
The sound is scored against profiles. 
*   **Plateau**: If metrics fall *anywhere* in the valid range, Score = 100% (1.0).
*   **Falloff**: Outside the range, the score decays effectively to 0.8 (80%).
*   **Threshold**: Final Score must exceed **78%**.

---

## 2. Recognized Profiles

### 🫰 Snaps
*   **Snap (High)**: >3500Hz. The crispest snap.
*   **Snap (Mid)**: 1000-4000Hz. Standard snap.
*   **Snap (Low)**: 800-2500Hz. Thicker snap. *Aggressively filtered to ignore voices*.

### ✋ Slaps
*   **Slap (Standard)**: 1000-5000Hz. skin-on-skin.
*   **Slap (Wet)**: 400-1500Hz. Heavy impact.
*   **Slap (Airy)**: 3000-10000Hz. Glancing contact. *Requires >50ms duration to ignore tongue clicks*.

### 🚪 Knocks
*   **Knock**: 50-3000Hz. Hard surface impact.
    *   **Max Tilt**: -5f. (Rejects "bright" clicks like pens or plastic snaps that have flat spectra).

---

## 3. False Positive Rejection Matrix

| Impostor | Why It fails |
| :--- | :--- |
| **Speech ("Hey")** | **Sustain Check**. Vowels last > 100ms. |
| **Clicks ("T"/"K")** | **Flux Gate**. Detected as clutter due to preceding syllable energy. |
| **Groans/Sighs** | **Late Peak Check**. Air blows ramp up slowly. Snaps peak instantly. |
| **Mic Pops** | **High-Pass Filter**. Most pop energy is < 300Hz. |
| **Table Bumps** | **Centroid**. Too deep (< 400Hz). |
| **Shuffling** | **Attack Threshold**. Rises too slowly. |
| **Audio Glitch** | **Sanity Check**. Detected via Digital Silence (0.0) history. |
| **Pill Rattling / Chaotic Noise** | **Chaos Rejection**. Pre-event variance too high (CV > 0.15) OR post-event still noisy. |

---

## 4. Tuning Guide

### Key Constants
*   `confidenceThreshold`: **0.78** (78%)
*   `thresholdMultiplier`: **4.0x** (Signal-to-Noise)
*   `attackThreshold`: **2.0x** (Rise Ratio)
*   `noiseAlphaFall`: **0.05** (Quick Recovery from loud events)
*   `preEventVarianceMax`: **0.15** (Max coefficient of variation in pre-event window)
*   `postEventCheckFrames`: **5** (50ms post-event quiet verification delay)
*   `postEventQuietRatio`: **0.5** (Post-event must drop to <50% of threshold)

### Troubleshooting
*   **Missed Snaps**: Ensure snap is sharp. Muffled/cloth snaps are filtered.
*   **False Trigger**: Check "Isolation" score. Noisy rooms raise the bar automatically.
*   **Rattling/Chaotic FPs**: Rejected by chaos detection (pre-event variance or post-event noise).

---

## 5. Audio Feedback System

The application uses a managed `SoundPool` architecture tailored for short, responsive UI and feedback sounds.

### Interaction Logic (The Mixer)
To prevent audio chaos during rapid interactions, the `SfxPlayer` implements intelligent stream management:

1.  **Anti-Spam (Cutoff)**:
    *   If a sound is requested (e.g., `CLICK`) while it is already playing, the *previous* instance is stopped immediately.
    *   Prevents "machine-gun" audio overlap effects on rapid button presses.

2.  **Auto-Ducking**:
    *   If a new sound starts while *different* sounds are playing, the existing sounds are momentarily ducked (volume reduced to 30%).
    *   Ensures the newest feedback event takes priority without abruptly silencing context.

3.  **Amplitude Protection (Clipping)**:
    *   If two sounds trigger within **50ms** of each other, the second sound is played at reduced volume (70%).
    *   Prevents digital clipping and distortion from summed waveforms.

### Asset Map
| Logical Event | Asset File | Role |
| :--- | :--- | :--- |
| **Timer Tick** | `pop_high` / `pop_low` | Metronome feedback. |
| **UI Click** | `metalclick` | General button interaction. |
| **Toggle** | `toggle_002` | Switch enable/disable state. |
| **Detect Success** | `drop_003` | Confirmation of valid snap/slap. |
| **Detect Fail** | `rollover6` | Feedback for invalid/rejected trigger. |
| **Alarm** | `alarm_clock_classic` | Timer completion. |

---

## 6. User Configuration & Feedback Settings

### Audio & Haptics Tab
The settings interface is divided into **General** and **Audio & Haptics** tabs to prioritize feedback customization.

### Detection Profiles (Bundled)
To simplify the user experience, detection complexity is abstracted into bundled profiles:
*   **Snap**: Optimizes for high-frequency sharp transients (fingers).
*   **Slap**: Optimizes for mid-frequency, broader impacts (hands).
*   **Knock**: Optimizes for lower-frequency solid impacts (knuckles).
*   **All**: Enables the full spectrum of detection logic.

### Haptic Hierarchy
1.  **System Master**: The app respects android `HAPTIC_FEEDBACK_ENABLED`. If the OS setting is off, the app stays silent tactilely.
2.  **App Override**: Users can disable haptics specifically for the app without changing system settings.

---

## 7. Implementation Notes (Architecture 3.3 Update)

### Resource Safety & Concurrency
The audio engine (`SoundDetector`) operates on a dedicated IO thread to avoid blocking the UI.
*   **Volatile State**: The `running` flag is marked `@Volatile` to ensure instant visibility of stop commands across threads.
*   **Robust Cleanup**: The recording loop is wrapped in a `try...finally` block. This guarantees that `recorder.release()` and `aec.release()` are called even if the coroutine is cancelled (e.g., via `stop()` or app backgrounding), preventing "Microphone in use" errors or native resource leaks.
*   **Blocking Read Protection**: `recorder` is now stored in a class-level variable, allowing `stop()` to explicitly call `release()`, which breaks any blocking `read()` operations in the background thread immediately.

### Lifecycle Management
Singleton services (`SoundDetector`, `SfxPlayer`, `VibrationHelper`) each manage their own `CoroutineScope`. To prevent memory leaks:
*   **Application-level Cleanup**: `CookTimerApp` observes `ProcessLifecycleOwner` and calls `release()` on all singletons when the app process is destroyed.
*   **Explicit Release Methods**: Each singleton exposes a `release()` method that cancels its internal scope and releases native resources (e.g., `SoundPool`, `AudioRecord`).
*   **TimerEngine Ticker**: The ticker job now properly stops when the timer is idle (COMPLETED, PAUSED, or READY without stopwatch). It exits the loop naturally instead of spinning indefinitely with `continue`.

### Performance Optimization
*   **Allocations**: FFT buffer allocations (`FloatArray`) are moved outside the hot loop to minimize Garbage Collection (GC) pauses during processing.
*   **Observation Strategy**: The `RunViewModel` separates settings observation (infrequent) from detector state observation (100Hz). This prevents the high-frequency detector updates from triggering expensive re-evaluations of startup logic.
*   **Idle Ticks**: The `TimerReducer` now short-circuits tick events when the timer is not running, preventing ~1Hz UI recompositions during idle states.
*   **Log Accumulation**: Detection logs are capped at 200 entries (down from 1000) to reduce memory footprint.
*   **Event Sample Snapshots**: When storing detection events, only the last 50 RMS samples (500ms) are captured instead of the full 150-frame history, reducing per-event memory allocation.
