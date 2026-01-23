# Sound Analysis Logs

To help refine detection, the app now outputs a structured CSV-style log for every sound event that crosses the initial volume threshold.

## How to Capture
1. Connect your device to Android Studio.
2. Open the **Logcat** tab.
3. In the filter bar, type: `tag:SoundAnalysis`.
4. Run the app and perform various sounds (Snaps, Claps, Background Noise, Speech).
5. Copy the output lines into a spreadsheet (Excel, Google Sheets).

## Column Breakdown

The log output follows this format:
`RESULT,CONFIDENCE,DURATION,RMS,PEAK,ZCR,CENTROID,TILT,ISOLATION|REASON`

| Column | Name | Description | Target / Notes |
| :--- | :--- | :--- | :--- |
| **0** | **Result** | `DETECTED_[PROFILE]` or `REJECTED`. | Filter by this to see what worked vs failed. |
| **1** | **Confidence** | `0.00` - `1.00` | Score of the best matching profile. We usually want > 0.60. |
| **2** | **Duration** | Milliseconds (`ms`) | **Snaps:** Short (<50ms). **Speech:** Long (>100ms). |
| **3** | **RMS** | Root Mean Square (0.0 - 1.0) | The "Volume" or energy of the event. |
| **4** | **Peak** | Raw Amplitude (0.0 - 1.0) | The absolute loudest point. Useful for clipping detection. |
| **5** | **ZCR** | Zero Crossing Rate | High frequency noise = High ZCR. **Snaps:** ~0.10-0.30. |
| **6** | **Centroid** | Spectral Centroid (Hz) | "Center of Gravity" of the frequency. **Snaps:** High (>2500Hz). |
| **7** | **Tilt** | Spectral Slope | Slope of frequency drop-off. **Snaps:** Positive/Flat. **Noise:** Negative. |
| **8** | **Isolation** | Pre-Noise / Event-RMS Ratio | How "sudden" the sound was. Lower is better. < 0.5 is ideal. |
| **9** | **Reason** | Text Description | Why the event was rejected (if applicable). Includes "Artifact" for wakes. |

## Optimization Strategy

### 1. "My snaps are being rejected"
- Look at the **REJECTED** lines corresponding to your snaps.
- Check the **Reason**.
- **If Reason is "Isolation":** The environment is too loud relative to the snap.
- **If Reason is "Low Confidence":** Check which metric is off.
    - Is **Duration** too long? (Maybe > 50ms?)
    - Is **Centroid** too low? (Maybe < 2000Hz?)
- Adjust the `SoundProfile` values in `SoundDetector.kt` to encompass these logged values.

### 2. "Clapping triggers it"
- Look at the `DETECTED_SNAP` lines that were actually claps.
- Compare them to real snaps.
- Claps usually have **Longer Duration** or **Lower Centroid**.
- Tighten the `weightDuration` or `weightCentroid` in the `SoundProfile` to penalize these differences more heavily.
