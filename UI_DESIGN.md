# UI Design System: Retro-Futuristic Industrial

## 1. Visual Language & Shapes

The aesthetic is inspired by **14-segment and 16-segment industrial displays**, moving away from standard material primitives to custom "Skinny Hexagon" geometry.

### The "Skinny Hexagon"
The core graphic primitive is a pointed, elongated hexagon used for all digital segments (ticks, bars, digit segments).

*   **Geometry**: A rectangle with 45-degree pointed tips on both ends.
*   **Usage**:
    *   **Circular Dial**: Radially oriented skinny hexagons.
    *   **Sound Indicator**: Vertically oriented skinny hexagons.
    *   **Segmented Digits**: Custom 16-segment font.

### Buttons & Controls
*   **Style**: Clean, thin-line rings.
*   **Texture**: Removed "Diamond" texture in favor of solid high-contrast borders.
*   **Colors**:
    *   **Primary Action (Lap)**: `Primary` color ring.
    *   **Destructive Action (Pause/Stop)**: `SoftRed` ring.

---

## 2. Sound Detection Indicator ("The Flux Bar")

A specialized UI component that visually separates "Hearing" from "Detecting".

### 2.1 The Two-Part System

#### A. The Microphone Circle (The Ear)
*   **Role**: Displays raw input volume (RMS).
*   **Behavior**: Pulses transparency based on ambient noise levels.
*   **Purpose**: Tells the user "I am listening."

#### B. The Linear Bar (The Brain)
*   **Role**: Displays detection confidence relative to the trigger threshold.
*   **Percentage Logic**:
    *   **0%**: Silence.
    *   **50%**: Input volume is half of the trigger threshold.
    *   **100%**: Threshold met (Trigger Event).
*   **Shape**: Row of 24 vertical "Skinny Hexagon" crystals.
*   **Animation Physics**:
    *   **Attack (Rise)**: Instant (30ms). Catches even the fastest transient snaps.
    *   **Decay (Fall)**: Fast-but-perceptible (150ms). Holds the peak just long enough for the human eye to register it before resetting.

### 2.2 Rejection Feedback
If a sound is loud enough but fails strict physics checks (e.g., speech), the bar remains at 0% or low levels, while the Text Overlay displays the specific failure reason (e.g., "Voice Ignored", "Too Long").

---

## 3. Color Palette Implementation

*   **SoftRed**: Used for Active State, Recording, and "Hot" UI elements.
*   **Primary**: Used for Navigation and Secondary Actions.
*   **Surface/Background**: Deep industrial dark mode by default.
