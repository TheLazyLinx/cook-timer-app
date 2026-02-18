# Sound Analysis and Diagnostics

This project now has two useful outputs for tuning:
- Logcat stream (`tag:SoundAnalysis`) for low-level detector events
- In-app diagnostics CSV export for structured event review

Use diagnostics CSV as the primary source when analyzing missed double/triple taps.

## How to Capture Diagnostics CSV

1. Open Settings -> Sound diagnostics.
2. Run tests (snap, clap, noise, intended gestures).
3. Export CSV from diagnostics.
4. Review in spreadsheet.

Current header:

`timestamp,outcome,rejectedAt,noiseFloor,threshold,preEventNoise,triggerRms,attackRatio,durationMs,peakRms,peakRaw,sustainRatio,decayRatio,peakFrame,preEventCV,postEventRms,postQuietThreshold,avgZcr,avgCentroid,avgTilt,avgFlatness,sessionSimilarity,bestProfile,bestConfidence,profileScores,detail,gestureGroup,gestureTapIndex,gestureTapCount,gesturePairingReason,note`

## Key Columns

- `outcome`: `ACCEPTED` or `REJECTED`
- `rejectedAt`: stage name (`SUSTAIN`, `DECAY`, `ISOLATION`, `CHAOS_PRE`, `CHAOS_POST`, `METALLIC`, `RESONANT_IMPACT`, `ULTRA_BRIGHT_PING`, `CADENCE_PATTERN`, `ATTACK_SHAPE`, `NUISANCE_SIMILARITY`, `ENVELOPE_RING`, `ENVELOPE_COMPLEX`, `SURFACE_TAP`, `HARD_SURFACE`, `STAGE2`, etc.)
- `detail`: human-readable rejection reason
- `bestProfile` / `bestConfidence`: top profile match
- `profileScores`: all profile confidences
- `avgFlatness`: broadband/noise-like spectral indicator
- `sessionSimilarity`: similarity to session-calibrated family reference (when available)

Gesture pairing fields:
- `gestureGroup`: group id for taps evaluated together
- `gestureTapIndex`: position in the group
- `gestureTapCount`: final resolved size (1/2/3)
- `gesturePairingReason`: why a chain stopped (for example no 3rd tap in grace window)

## Detector vs Action

`DETECTED_*` in logs means detector-level acceptance, not guaranteed control action.

For Stopwatch/Timer/Run actions, taps must also:
- pass action-quality filtering (`isActionableFreeGestureEvent`)
- form a valid double/triple inside recognizer windows (`1200ms` tap window, `800ms` triple grace)

If you see detector accepts but no behavior, check gesture fields first:
- empty `gestureGroup` usually means the event was not routed into gesture grouping
- `gestureTapCount=1` means it did not pair in time

## How to Read Gesture Failures

1. Start from `ACCEPTED` rows only.
2. Group by `gestureGroup`.
3. Check `gestureTapCount`:
- `1`: no second tap inside window
- `2`: no third tap inside triple grace window
- `3`: successful triple
4. For missing taps, inspect neighboring `REJECTED` rows by timestamp and `rejectedAt`.
5. If taps are present but do not form a double/triple, check `gesturePairingReason` for:
   - `Resonant impact-like sequence, treated as singles`

## Common Patterns

- Many `SUSTAIN`/`DECAY` rejections near attempted triples:
  - impact tail is too long for current envelope checks
- `CHAOS_PRE` clusters:
  - environment already noisy before impact
- `CHAOS_POST` with ringing detail:
  - post-impact ringing stayed too energetic
- `METALLIC` / `ENVELOPE_RING`:
  - likely non-gesture ringing impact (cutlery, key-like or ceramic ping)
- `RESONANT_IMPACT`:
  - consistent resonant object-contact signatures (for example knife on glass) that profile-match as snap/clap
- `ULTRA_BRIGHT_PING`:
  - short, high-centroid/high-ZCR pings typical of crisp metal/glass contacts
- `CADENCE_PATTERN`:
  - periodic stream suppression for rhythmic non-gesture impacts
  - detail includes cadence stats (`n`, mean interval, interval CV)
- `NUISANCE_SIMILARITY`:
  - event is very close to previously rejected nuisance signatures from the same session
  - detail includes reference counts as `refs=total/strong`
- `LOW_FLATNESS` / `SESSION_MISMATCH`:
  - often tonal speech/object sounds or impacts dissimilar to current calibrated gesture family

## Logcat Stream (Optional)

Logcat with `tag:SoundAnalysis` is still useful for live debugging, but it is less convenient than diagnostics CSV for gesture-chain analysis.

Use both when needed:
- CSV for precise grouping and rejection stages
- Logcat for fast live iteration while testing
