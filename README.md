# Cook Timer App

A simple Android (Jetpack Compose) app for running step-based cooking timers. The app models presets made of steps, and a timer engine moves through the steps with basic controls (start/pause/resume/next/back).

## What’s in this repo

- `app/` – Android application module.
  - `app/src/main/java/ro/maris/tools/cooktimer/MainActivity.kt` – App entry point and Compose setup.
  - `app/src/main/java/ro/maris/tools/cooktimer/ui/` – UI screens and theme.
  - `app/src/main/java/ro/maris/tools/cooktimer/domain/` – Timer engine and data models.
- `MVP_DESIGN.md` – Product/design notes for the cooking timer MVP.
- Gradle files (`build.gradle.kts`, `settings.gradle.kts`) – build configuration.

## High-level flow

1. Presets are defined as ordered steps (see domain models).
2. The timer engine tracks the active step, phase, and remaining time.
3. The UI renders the current step and exposes basic controls.

## Notes

- This repository focuses on a minimal MVP timer experience (no recipes or cloud sync).
- Tests are present under `app/src/test` and `app/src/androidTest`.
