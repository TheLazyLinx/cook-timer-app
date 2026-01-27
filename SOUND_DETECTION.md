# Sound Detection System (Architecture 3.1)

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

### Step D: Spectral Profiling (The Fingerprint)
The isolated impact is analyzed for:
1.  **Centroid (Center of Gravity)**: Must be > 800Hz. (Deep sounds are ignored).
2.  **ZCR (Texture)**: Must be "noisy" enough (friction), but not *too* noisy (shushing).
3.  **Tilt (Shape)**: Must be relatively flat. (Speech has a steep bass-heavy tilt).

### Step E: Soft-Plateau Scoring
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

---

## 4. Tuning Guide

### Key Constants
*   `confidenceThreshold`: **0.78** (78%)
*   `thresholdMultiplier`: **4.0x** (Signal-to-Noise)
*   `attackThreshold`: **2.0x** (Rise Ratio)
*   `noiseAlphaFall`: **0.05** (Quick Recovery from loud events)

### Troubleshooting
*   **Missed Snaps**: Ensure snap is sharp. Muffled/cloth snaps are filtered.
*   **False Trigger**: Check "Isolation" score. Noisy rooms raise the bar automatically.
