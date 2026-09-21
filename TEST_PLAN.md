# Test plan

## Goal

Determine whether 60 fps irregularity is already present in Camera2 sensor timestamps, appears only after the camera callback path, or first appears at MediaCodec output.

## Automatic suite

The app dynamically includes only configurations reported by the selected back camera. Candidate profiles are:

- 1920x1080 30 fps, preview ON
- 1920x1080 60 fps, preview ON
- 1920x1080 60 fps, preview OFF
- 3840x2160 30 fps, preview ON
- 3840x2160 60 fps, preview ON
- 3840x2160 60 fps, preview OFF

Each profile records for 10 seconds and waits 2 seconds before the next profile. EIS and OIS are requested OFF to reduce hidden processing. Audio is intentionally not recorded.

## Recommended comparisons

Run A: phone cool, battery above 50%, unplugged, airplane mode optional.

Run B: immediately after 5-10 minutes of continuous 4K60 camera use.

Run C: same physical scene as A, but with the phone connected to power.

Do not change lighting or movement pattern within a comparison pair if possible.

## Primary evidence

1. `*_camera.csv`: Camera2 capture result sensor timestamp cadence.
2. `*_encoder.csv`: encoded sample PTS cadence.
3. `*_thermal.csv`: Android thermal status and battery temperature.
4. `*_summary.json`: compact metrics and device/camera capability dump.
5. matching `.mp4`: visible motion result.

## Interpretation

If `camera.csv` already shows ~33.3 ms at a 60 fps target, one capture period was skipped before the app could encode a unique 16.67 ms cadence.

If camera timestamps stay near 16.67 ms but `encoder.csv` has ~33.3 ms gaps, investigate encoder/surface/muxer load.

If both timestamp series are healthy but the image visibly repeats or jumps, the next test should inspect pixel-content duplication. That requires a second, deliberately separate diagnostic build because adding an ImageReader to this first test could change the very performance we are trying to measure.
