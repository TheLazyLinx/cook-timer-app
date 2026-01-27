# CookTimer2

A step-based cooking timer Android app built with Jetpack Compose. Run reusable timing flows (presets) with linear steps, auto/manual start modes, and optional hands-free sound gesture control.

## Overview

CookTimer2 is a simple, reliable cooking timer that focuses on **processes**, not recipes. Create and run presets for common cooking tasks like "Boil", "Bake", "Pan Fry", etc., each with configurable steps that can have timers or act as manual gates.

## Features

- **Preset Management**: Create, edit, and run custom timer presets with multiple steps
- **Step Configuration**: 
  - Timed steps with customizable durations
  - Gate steps (wait until ready, no timer)
  - Auto or manual start modes per step
  - Optional notes for each step
- **Timer Engine**: State machine-based timer with start/pause/resume/next/back controls
- **Sound Gesture Control**: Optional hands-free slap/knock detection for timer control
- **Haptic Feedback**:
  - Vibration confirmation for state transitions (user actions, timer completion, step changes)
  - "Clock Tick" feedback for valid sound triggers
  - Virtual key feedback for future functionality (Lap)
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
│   ├── presets/           # Presets list screen and ViewModel
│   ├── edit/              # Preset editor screen and ViewModel
│   ├── run/               # Timer running screen and ViewModel
│   ├── sound/             # Sound test screen and ViewModel│   │   ├── haptics/           # Vibration helper and implementation│   ├── theme/             # Material3 theming (Color, Type, Theme)
│   └── navigation/        # Navigation setup (AppNavHost, Routes)
├── audio/                 # Sound effects player and detector
└── di/                    # Dependency injection modules (Hilt)
```

### Tech Stack

- **UI**: Jetpack Compose with Material3
- **Architecture**: MVVM with ViewModels and StateFlow
- **Dependency Injection**: Hilt/Dagger
- **Navigation**: Jetpack Navigation Compose
- **Database**: Room with SQLite
- **Audio**: AudioRecord for sound detection, SoundPool for effects
- **Haptics**: Android VibrationEffect and VibratorManager
- **Build**: Gradle with Kotlin DSL, KSP for annotation processing

## Key Components

### Domain Models

- **Preset**: Reusable timing flow with ordered steps
- **Step**: Individual stage with duration, start mode, and optional notes
- **RunState**: Ephemeral timer state tracking current step, phase, and time
- **Phase**: Timer state enum (READY, RUNNING, PAUSED, COMPLETED)

### Timer Engine

State machine-based engine using a reducer pattern:
- Processes actions (Tick, Start, Pause, Resume, Next, Back, etc.)
- Maintains immutable state transitions
- Handles step progression and auto-start logic

### UI Screens

1. **Presets List**: Browse built-in and custom presets, create new ones
2. **Edit Preset**: Add/edit/reorder steps, configure step properties
3. **Run Timer**: Execute a preset with timer controls and sound gestures
4. **Sound Test**: Test and visualize sound detection sensitivity

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
