# Normalized IMD (dR/R0, dF/F0) for ImageJ/Fiji

An ImageJ/Fiji plugin that creates **relative-change** intensity-modulated displays:
**dR/R0** (ratiometric FRET) or **dF/F0** (single-channel fluorescence), modulated by
signal intensity. Derived from the
[Intensity Modulated Display (IMD)](https://github.com/yugo8849/imd-imagej) platform.

## Overview

Where IMD shows the ratio `R = FRET/CFP` itself, Normalized IMD shows its **relative
change** against a user-supplied baseline `R0` (or `F0`). The color encodes the relative
change; the brightness encodes the background-subtracted signal, so background regions
fade to black and only real signal carries color.

**Key idea — subtract background before normalizing.** If the baseline still contains a
background offset, the denominator is inflated and the relative change is underestimated.
Normalized IMD therefore subtracts a background value from both the pixel data and the
baseline before computing the ratio/normalization.

## Modes

- **dR/R0 - two images (FRET, CFP)** — pick FRET and CFP images separately.
- **dR/R0 - multi-channel stack** — pick one hyperstack and the acceptor/donor channels.
- **dF/F0 - single image** — pick one fluorescence image.

## How the background is determined

Two complementary things happen:

1. **Per-pixel images are background-subtracted spatially** by the **Subtract Background
   Plus** plugin (FRET, CFP, or F). This removes the background across the field so the
   final image's background regions go dark.
2. **The baseline R0 / F0 uses a scalar background.** Subtract Background Plus is run in
   *Create background* mode and the resulting background image is **averaged over a frame
   range** you specify (e.g. frames 1-5) into one scalar per channel. This keeps the
   baseline consistent with the raw scalar `FRET0` / `CFP0` / `F0` you enter.

## Math

**dR/R0** (FRET, CFP spatially background-subtracted -> `FRET_sub`, `CFP_sub`):

```
R     = FRET_sub / CFP_sub
R0    = (FRET0 - FRET_bg) / (CFP0 - CFP_bg)      (scalar background)
dR/R0 = R / R0 - 1                                (color)
brightness = chosen source: CFP_sub, FRET_sub, or (CFP_sub + FRET_sub)/2
```

**dF/F0** (F spatially background-subtracted -> `F_sub`):

```
dF/F0 = F_sub / (F0 - F_bg) - 1                   (color)
brightness = F_sub
```

## Baseline (R0 / F0)

The baseline can be obtained two ways, chosen in the first dialog:

- **ROI (pre-stimulus mean)** — *default, recommended.* You supply an area ROI (draw it on
  the image, or it is requested when the plugin runs). The plugin measures the ROI mean on
  the **background-subtracted** images over the pre-stimulus frame range. For dR/R0 it takes
  the per-frame ratio of ROI means and averages over those frames; for dF/F0 it averages the
  ROI mean. No scalar-background workaround is needed, so this is the more accurate path.

- **Fixed value** — you type raw `FRET0` / `CFP0` / `F0` (ROI means measured before
  background subtraction). The plugin subtracts a scalar background — the mean of the SBP
  "Create background" image over the pre-stimulus frames — to form
  `R0 = (FRET0 - FRET_bg)/(CFP0 - CFP_bg)` or `F0 - F_bg`.

In both cases the per-pixel images are background-subtracted spatially before the relative
change is computed.

## Requirements

- ImageJ 1.53c or later / Fiji
- [Subtract Background Plus](https://github.com/yugo8849/subtract-background-plus) plugin
  (required when background subtraction is enabled)
- Operating System: Windows, Mac, or Linux

## Installation

1. Download `Normalized_IMD-1.1.0.jar`
2. Copy it to ImageJ's `plugins/` folder
3. Also install `Subtract Background Plus` in the same `plugins/` folder
4. Restart ImageJ/Fiji

The plugin appears in the **Plugins > FRET** menu as **Normalized IMD (dR/R0, dF/F0)**.

## Quick start

1. Open your data (two FRET/CFP images, one multi-channel stack, or one fluorescence image).
2. Run **Plugins > FRET > Normalized IMD**.
3. Choose the mode and the **baseline source** (ROI or Fixed value).
4. In the main dialog set the image(s)/channels, background method/radius/smoothing, the
   **pre-stimulus frame range**, the **Delta** (color) and **Intensity** (brightness) ranges,
   the brightness source (dR/R0), and the LUT.
5. If you chose **ROI** baseline: draw an area ROI over the baseline cell region when prompted
   (or have one already on the image), then click OK. R0/F0 is measured automatically.
   If you chose **Fixed value**: the dialog asks for `FRET0`/`CFP0` or `F0`.
6. Use **Test mode** to process only the first frame while tuning, then run the full stack.

## Parameters

- **Baseline R0/F0** — ROI (pre-stimulus mean) or Fixed value.
- **FRET0 / CFP0 / F0** — raw baseline ROI means (Fixed-value mode only).
- **Subtract background** — estimate background with Subtract Background Plus.
- **BG method / radius / smoothing sigma** — passed to Subtract Background Plus.
- **Pre-stimulus frames - first/last** — frames over which the baseline is measured
  (ROI mode) or the scalar background is averaged (Fixed mode).
- **Delta max / min** — display range of dR/R0 or dF/F0 (color).
- **Brightness source** (dR/R0 only) — CFP, FRET, or their average; the
  background-subtracted signal used for intensity modulation.
- **Intensity max / min** — display range of the background-subtracted signal (brightness).
- **LUT** — color map for the relative change.
- **Test mode / Multi-threaded / Save parameters** — processing options.

## Output

An RGB image titled `NIMD-dRR0-...` or `NIMD-dFF0-...`, where color encodes the relative
change and brightness encodes the background-subtracted signal. Parameters are saved to
`NIMD_parameters.txt` in the ImageJ directory.

## Notes

- The frame range is interpreted over the stack's slice order (single-channel time-lapse).
- For dR/R0, the plugin requires `CFP0 - CFP_bg > 0` (it aborts otherwise) and warns if
  `FRET0 - FRET_bg <= 0`. For dF/F0 it requires `F0 - F_bg > 0`.

## License

MIT License - see [LICENSE](LICENSE).

## Acknowledgments

- Derived from the Intensity Modulated Display (IMD) plugin.
- Background estimation by the Subtract Background Plus plugin.
- Developed with assistance from Claude (Anthropic).

---

**Version 1.1.0**
