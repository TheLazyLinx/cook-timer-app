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

## 2. Sound Detection Indicator (Retro Arc)

A compact dual-arc indicator that separates live volume from confirmed detection.

### 2.1 The Two-Part System

#### A. The Microphone Arc (The Ear)
*   **Role**: Displays raw input volume (RMS).
*   **Shape**: 18 narrow "Skinny Hexagon" segments arranged in a 180-degree arc.
*   **Behavior**: VU-style progression with fast attack and quick decay.
*   **Core**: Green mic icon inside a thin-ring circle.

#### B. The Detection Arc (The Brain)
*   **Role**: Displays confirmed detection pulses, not raw loudness.
*   **Shape**: 18 narrow "Skinny Hexagon" segments in a mirrored 180-degree arc.
*   **Core**: Blue ring with two semicircle dots that move toward each other during a detection pulse.
*   **Flash**: A short blue flash on detection (150ms) that always resets.

#### C. Center Error Message
*   **Role**: Shows friendly rejection text between the two arcs.
*   **Behavior**: Short-lived and only visible after a failed detection.

### 2.2 Layout & Scale
*   **Indicator Height**: 80dp (scaled up for clarity).
*   **Core Scale**: 0.58 of the arc diameter.
*   **Center Text Width**: 180dp, fixed to avoid layout jitter.
*   **Spacing**: 24dp between arc and text blocks.

---

## 3. Color Palette Implementation

*   **SoftRed**: Used for Active State, Recording, and "Hot" UI elements.
*   **Primary**: Used for Navigation and Secondary Actions.
*   **Surface/Background**: Deep industrial dark mode by default.

---

## 4. Display Style Toggle

Segmented digits are the default visual language. A General settings toggle can disable segmented digits and show standard monospace text for all numeric displays.
