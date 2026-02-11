# CookTimer2

A step-based cooking timer Android app built with Jetpack Compose. Run reusable timing flows (presets) with linear steps, auto/manual start modes, and optional hands-free sound gesture control.

## Overview

CookTimer2 is a simple, reliable cooking timer that focuses on **processes**, not recipes. Create and run presets for common cooking tasks like "Boil", "Bake", "Pan Fry", etc., each with configurable steps that can have timers or act as manual gates.

## Features

- **Multi-Mode Operation**:
  - **Presets**: Create and run custom flows with multiple steps (e.g., "Boil", "Bake").
  - **Quick Timer**: Simple countdown timer with a custom duration picker.
  - **Stopwatch**: Standard stopwatch with **Lap** functionality.
- **Improved Navigation**: Tab-based interface (Presets, Timer, Stopwatch, Settings).
- **Preset Management**: Create, edit, and run custom timer presets with multiple steps
- **Step Configuration**: 
  - Timed steps with customizable durations
  - Gate steps (wait until ready, no timer)
  - Auto or manual start modes per step
  - Optional notes for each step
- **Timer Engine**: State machine-based timer with start/pause/resume/next/back controls
- **Sound Gesture Control**: Optional hands-free snap/clap detection for timer control. Supports safe multi-screen usage with fixed free-mode gesture behavior:
  - Double tap: Start / Pause / Resume
  - Triple tap: Stopwatch = Lap, Preset Run = Parse
  - Action routing uses an additional quality filter before gesture grouping to reduce kitchen false positives
- **Haptic Feedback**:
  - Vibration confirmation for state transitions (user actions, timer completion, step changes)
  - "Clock Tick" feedback for valid sound triggers
  - Virtual key feedback for "Lap" and "Picker" interactions.
- **Dark/Light Theme**: System-aware theming with manual override
- **Local Storage**: Room database for persistent preset storage
- **Built-in Presets**: Pre-configured templates that can be copied and customized

## Architecture

The app follows clean architecture principles with clear separation of concerns:

### Project Structure

```
app/src/main/java/ro/maris/tools/cooktimer/
├── MainActivity.kt          # Entry point, Compose setup
├── CookTimerApp.kt         # Application class with Hilt
├── domain/
│   ├── model/              # Core data models (Preset, Step, RunState, Phase)
│   ├── engine/             # Timer engine with reducer pattern (TimerEngine, TimerReducer)
│   └── repository/         # Repository interface (PresetRepository)
├── data/
│   ├── local/
│   │   ├── db/            # Room database (AppDatabase)
│   │   ├── dao/           # Data access objects (PresetDao, StepDao)
│   │   └── entity/        # Room entities (PresetEntity, StepEntity)
│   └── repository/        # Repository implementation (RoomPresetRepository)
├── ui/
│   ├── main/              # Main scaffold with Bottom Navigation
│   ├── presets/           # Presets list screen and ViewModel
│   ├── edit/              # Preset editor screen and ViewModel
│   ├── run/               # Preset execution screen and ViewModel
│   ├── timer/             # Quick Timer screen and ViewModel
│   ├── stopwatch/         # Stopwatch screen and ViewModel
│   ├── settings/          # Settings screen and ViewModel
│   ├── sound/             # Sound test components
│   │   ├── haptics/       # Vibration helper and implementation
│   ├── theme/             # Material3 theming (Color, Type, Theme)
│   └── navigation/        # Navigation setup (AppNavHost, Routes)
├── audio/                 # Sound effects player and detector (Reference Counting)
└── di/                    # Dependency injection modules (Hilt)
```

### Tech Stack

- **UI**: Jetpack Compose with Material3
- **Architecture**: MVVM with ViewModels and StateFlow
- **Dependency Injection**: Hilt/Dagger
- **Navigation**: Jetpack Navigation Compose (Tab-based)
- **Database**: Room with SQLite
- **Audio**: AudioRecord (Singleton with Ref-Counting), SoundPool
- **Haptics**: Android VibrationEffect and VibratorManager
- **Build**: Gradle with Kotlin DSL, KSP for annotation processing

## Key Components

### Domain Models

- **Preset**: Reusable timing flow with ordered steps
- **Step**: Individual stage with duration, start mode, and optional notes
- **RunState**: Ephemeral timer state tracking current step, phase, time, and **laps**.
- **Phase**: Timer state enum (READY, RUNNING, PAUSED, COMPLETED)

### Timer Engine

State machine-based engine using a reducer pattern:
- Processes actions (Tick, Start, Pause, Resume, Next, Back, Lap, etc.)
- Maintains immutable state transitions
- Handles step progression and auto-start logic

### UI Screen Structure

The app uses a `MainScreen` scaffold with a bottom navigation bar:
1. **Presets**: Browse built-in and custom presets.
2. **Timer**: Quick countdown timer.
3. **Stopwatch**: Standard stopwatch with laps.
4. **Settings**: App preferences (Sound, Theme).

## Requirements

- **Minimum SDK**: 26 (Android 8.0)
- **Target SDK**: 36
- **Compile SDK**: 36
- **Kotlin**: 2.0.21
- **Gradle**: 8.13.2

## Building

```bash
./gradlew assembleDebug
```

## Running

```bash
./gradlew installDebug
```

Or open in Android Studio and run directly.

## Design Documents

- [MVP_DESIGN.md](MVP_DESIGN.md) – Detailed product/design specifications for the MVP
- [AGENT.md](AGENT.md) – Development agent instructions

## Permissions

- `RECORD_AUDIO`: Required for optional sound gesture detection feature

## License

See project documentation for license details.
