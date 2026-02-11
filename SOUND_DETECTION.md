# Sound Detection System

## Overview

CookTimer2 uses a physics-first transient detector. It is designed to accept short, sharp snap/clap-like impacts and reject speech, continuous noise, and ringing impacts (for example metal on metal or metal on ceramic).

The detector runs in `SoundDetector` and emits:
- accepted events used for gesture recognition
- structured diagnostics for tuning/export

In run screens, detector acceptance is not the final action decision. A per-screen action-quality filter (`isActionableFreeGestureEvent`) is applied before taps are fed into gesture grouping.

## Free Mode Gesture Behavior

Free mode uses fixed gesture mapping:
- Double tap: Start / Pause / Resume (`TOGGLE_PLAY_PAUSE`)
- Triple tap:
  - Stopwatch: Lap
  - Preset Run: Parse (mapped to next-step style action in current flow)
  - Quick Timer: no triple-tap action

Gesture grouping is done by `SoundTapGestureRecognizer`:
- tap window: `1200ms`
- duplicate suppression: `<90ms`
- triple grace after double: `800ms`

Important:
- `DIAGNOSTIC` output shows detector-level acceptance/rejection.
- Stopwatch/Timer/Run action execution uses detector output plus action-quality filtering plus grouping windows.

## Detection Pipeline

### 1. Trigger and Event Tracking

A candidate event starts when loudness and attack constraints are met, using high-pass assisted triggering.

Core ideas:
- dynamic threshold from noise floor
- attack ratio gate
- event monitored frame-by-frame (10ms windows)

### 2. Temporal Shape Checks

While monitoring an event, several checks reject non-impact shapes:
- `SUSTAIN`: too much energy remaining around 50-60ms
- `LATE_PEAK`: peak arrives too late
- `REBOUND`: multi-peak bounce shape
- `DURATION`: event too long
- `DECAY`: tail does not drop fast enough

`SUSTAIN` and `DECAY` are context-sensitive and relaxed for likely follow-up taps in a gesture chain.

### 3. Spectral Profile Scoring

The event is scored against profile families:
- `Snap (High)`
- `Snap (Mid)`
- `Snap (Low)`
- `Clap (Airy)`
- `Clap`
- `Clap (Wet)`
- `Knock`

A snap bias is applied when snap and clap scores are close for short bright impulses.

### 4. False Positive Rejection Layers

After profile match, additional gates reject likely impostors:
- `ENVELOPE_COMPLEX`: complex tail/rise envelope inconsistent with intended transient shape
- `SURFACE_TAP`: table/body-coupled low-frequency impact signature
- `HARD_SURFACE`: hard contact signature (object collisions) with constrained attack/tail
- `ISOLATION`: too much pre-event noise relative to event strength
- `CHAOS_PRE`: too many pre-event spikes in the lookback window
- `METALLIC`: bright, positively tilted metallic-like transients
- `ENVELOPE_RING`: ringing envelope shape (bouncy/flat tail)
- `STAGE2`: independent agreement score below threshold

Chaos handling includes signature-aware whitelisting for valid follow-up taps so double/triple gestures are not blocked by the first tap tail.

### 5. Post-Event Validation

Accepted pre-check events are held briefly, then validated:
- uses max RMS observed during post-event check window (not only final frame)
- enforces post-quiet threshold and ring ratio limit

This catches brief ringing bursts that would otherwise pass if only the final sample is quiet. Failures at this stage are emitted as `CHAOS_POST`.

### 6. Diagnostics and CSV Export

Diagnostics contain stage-level details for every accepted/rejected event, including:
- profile confidence and per-profile scores
- pre/post event metrics
- gesture grouping annotation

Gesture fields:
- `gestureGroup`
- `gestureTapIndex`
- `gestureTapCount`
- `gesturePairingReason`

These are populated after recognizer grouping so you can see exactly which accepted taps formed singles/doubles/triples and why a group did not extend.

### 7. Notes on Profiles and Product Scope

Current free-mode gesture recognition filters to snap/clap family events in app flows.

`Knock` remains in the detector profile set for tuning and compatibility, but free-mode action routing currently uses snap/clap family events.

`Snap (Low)` may still appear in diagnostics/profile matching, but free action routing currently rejects it to reduce false positives.

Legacy `Slap` naming remains tolerated in some compatibility paths, but UI and settings use `Clap` naming.
