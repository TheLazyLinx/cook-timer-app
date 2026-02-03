# Cooking Stopwatch --- MVP Design Document (v1)

## Purpose

Build a simple, reliable, step-based cooking timer that allows users to
run reusable timing **flows (presets)** with: - linear steps, - per-step
**Auto vs Manual (Wait)** start, - optional **hands-free slap/knock
control**.

This app is **not** a recipe app.

------------------------------------------------------------------------

## Core Concepts

### Preset

A reusable, named timing flow composed of ordered steps.

-   Presets represent **processes**, not instructions.
-   Examples: "Boil", "Bake", "Pan Fry", "Popcorn".
-   Users rename and adapt presets freely.

------------------------------------------------------------------------

### Step

A single stage in a preset.

Each step may: - have a duration (timer), - or act as a gate ("Wait
until ready"), - start automatically or require manual start.

------------------------------------------------------------------------

## Data Model

### Preset

``` ts
Preset {
  id: string
  name: string
  steps: Step[]
  isBuiltIn: boolean
  createdAt: number
  updatedAt: number
}
```

### Step

``` ts
Step {
  id: string
  title: string
  durationSec: number | null
  startMode: "auto" | "manual"
  notes?: string
}
```

### Lap

``` ts
Lap {
  index: number
  lapTimeMs: number
  splitTimeMs: number
}
```

### Run State (ephemeral)

``` ts
RunState {
  presetId: string
  activeStepIndex: number
  phase: "ready" | "running" | "paused" | "completed"
  remainingSec: number
  totalDurationMs: number // Stopwatch elapsed
  laps: Lap[] // Stopwatch laps
  startedAtEpoch?: number
  micListening: boolean
  lastSoundTriggerEpoch: number
}
```

------------------------------------------------------------------------

## Free vs Premium (MVP Scope)

### Free Tier

-   Built-in presets (viewable + copyable)
-   **1 custom preset** (fully editable)
-   Unlimited steps per preset
-   Full timer functionality
-   Slap/knock gesture control (with confirmation)

### Premium Tier

-   **Unlimited custom presets**
-   No additional power features in MVP

### Haptics & Feedback

-   **Sound Triggers**: Valid sound triggers (snaps, claps) provide a "Clock Tick" vibration feedback.
-   **State Transitions**: Any significant state change (Start, Pause, Resume, Step Change, Timer Completion) provides a "Confirm" (stronger) vibration.
-   **Stopwatch**: A virtual "Lap" key (future feature) provides "Click" feedback currently wired to a UI button.

------------------------------------------------------------------------

## App Screens

### 1. Presets List

-   Sections:
    -   Built-in Presets (templates)
    -   My Presets
-   Actions:
    -   Run preset
    -   Duplicate built-in preset
    -   Create new preset (disabled if free limit reached)

------------------------------------------------------------------------

### 2. Preset Editor

-   Edit preset name
-   Step list:
    -   Add / delete / reorder steps
-   Step editor:
    -   Title
    -   Duration (optional)
    -   Start mode (Auto / Manual)
    -   Notes (optional)

------------------------------------------------------------------------

### 3. Run Screen

-   Shows only the current step
-   Displays:
    -   Step title
    -   Notes
    -   Remaining time (if applicable)
    -   Next step preview
-   Controls:
    -   Start (if manual)
    -   Pause / Resume
    -   Next step
    -   Previous step
-   Microphone listening indicator (if enabled)

------------------------------------------------------------------------

### 4. Completion Screen

-   Flow complete
-   Actions:
    -   Restart preset
    -   Return to presets list

------------------------------------------------------------------------

### 5. Settings

-   Enable / disable slap or knock gesture
-   Gesture test
-   Sound & vibration toggles

------------------------------------------------------------------------

## Timer Engine State Machine

### Phases

-   `ready`: step loaded, waiting for user start
-   `running`: timer active
-   `paused`: timer paused
-   `completed`: final step finished

------------------------------------------------------------------------

### Step Entry Logic

-   If `startMode = manual` → phase = `ready`
-   If `startMode = auto` and `durationSec != null` → phase = `running`
-   If `durationSec = null` → phase = `ready`

------------------------------------------------------------------------

### Transitions

``` text
ready → running        (Start)
running → paused      (Pause)
paused → running      (Resume)
running → ready       (Auto-advance to manual step)
running → running     (Auto-advance to auto step)
running → completed   (Last step ends)
```

-   Timer completion triggers sound + vibration.
-   Background-safe using epoch-based timing and scheduled
    notifications.

------------------------------------------------------------------------

## Sound Gesture MVP (Slap / Knock)

### Description

Detect a short, high-energy impulse sound to control the app hands-free.

------------------------------------------------------------------------

### Rules

-   Global opt-in only
-   Visible listening indicator
-   Cooldown to prevent repeated triggers
-   **Always requires user confirmation** in MVP

------------------------------------------------------------------------

### Gesture Actions (Context-Aware)

-   If phase = `ready` → "Start step?"
-   If phase = `running` → "Next step?"
-   If phase = `paused` → "Resume?"

Only one gesture type exists in MVP.

------------------------------------------------------------------------

## Storage & Offline

-   Local storage only
-   No accounts
-   No cloud sync
-   App must function fully offline

------------------------------------------------------------------------

## Explicit Non-Goals (Out of Scope)

-   Recipes or cooking instructions
-   Ingredient lists or todo lists
-   Temperature tracking
-   Sound classification (boil, popcorn)
-   Voice recognition
-   Cloud sync or sharing
-   Analytics
-   Ads

------------------------------------------------------------------------

## MVP Acceptance Criteria

The MVP is complete when: - A free user can create, rename, and run
**one** custom preset end-to-end. - Manual (Wait) steps prevent
real-world timing drift. - Timers run reliably in background with
notifications. - Slap/knock gesture works with confirmation and does not
trigger silently. - Premium unlock only affects preset count, not timer
behavior.

------------------------------------------------------------------------

## Future (Post-MVP, Non-Binding)

-   Ingredient list per preset
-   Todo checklist per preset
-   Advanced sound triggers (boil, popcorn)
-   Gesture auto-confirm
-   Preset history & reuse
-   Import / export presets
