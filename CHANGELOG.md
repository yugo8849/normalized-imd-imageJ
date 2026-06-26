# Changelog

All notable changes to the Normalized IMD plugin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-06-25

### Added
- **ROI (pre-stimulus mean) baseline source.** In addition to entering a fixed value,
  R0 / F0 can now be measured from a user-supplied area ROI on the background-subtracted
  images over the pre-stimulus frame range (per-frame ratio of ROI means averaged for
  dR/R0; ROI mean averaged for dF/F0). The ROI can be drawn beforehand or when prompted.
  This needs no scalar-background workaround and is generally more accurate.

### Fixed
- ROI-based measurement returned `R0 = NaN` when an ROI was present on the source image,
  because the ImageJ Duplicator crops to a present area ROI's bounding box. All source-image
  duplications now ignore the source ROI (the ROI is temporarily removed for duplication and
  restored), so working images stay full-frame and the measurement ROI is applied correctly.

## [1.0.0] - 2026-06-25

### Added
- Initial release, derived from the Intensity Modulated Display (IMD) platform.
- Three modes: `dR/R0` (two images), `dR/R0` (multi-channel stack), `dF/F0` (single image).
- Per-pixel images are background-subtracted **spatially** by the Subtract Background Plus
  plugin, so the final image has its background removed.
- Fixed-value baseline: user-entered raw FRET0/CFP0/F0, with a scalar background (mean of
  the SBP "Create background" image over the pre-stimulus frames) subtracted to form R0/F0.
- Selectable brightness source for dR/R0 (CFP, FRET, or average), like IMD.
- Adjustable Delta (color) and Intensity (brightness) display ranges, dynamic LUT
  selection, Test mode, multi-threaded processing, and parameter persistence
  (NIMD_parameters.txt).

### Requirements
- Requires the [Subtract Background Plus](https://github.com/yugo8849/subtract-background-plus)
  plugin when background subtraction is enabled.
