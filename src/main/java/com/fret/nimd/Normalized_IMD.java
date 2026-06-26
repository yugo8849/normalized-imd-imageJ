package com.fret.nimd;

import ij.*;
import ij.gui.*;
import ij.plugin.*;
import ij.process.*;
import ij.measure.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Normalized Intensity Modulated Display (Normalized IMD)
 *
 * Relative-change visualization with intensity modulation, derived from the
 * Intensity Modulated Display (IMD) platform. Two normalization modes:
 *
 *   dR/R0 (ratiometric):  color = (FRET_sub / CFP_sub) / R0 - 1
 *   dF/F0 (single channel): color = F_sub / F0 - 1
 *
 * where *_sub are images background-subtracted SPATIALLY by Subtract Background Plus.
 *
 * Baseline (R0 / F0) can be obtained two ways:
 *
 *   - Fixed value: the user enters raw FRET0/CFP0/F0 ROI means; the plugin subtracts a
 *     scalar background (mean of the SBP "Create background" image over a frame range)
 *     to form R0 = (FRET0-FRET_bg)/(CFP0-CFP_bg) or F0-F_bg.
 *
 *   - ROI (pre-stimulus mean): the user supplies an ROI; the plugin measures the ROI mean
 *     on the background-subtracted images over the pre-stimulus frame range. For dR/R0 it
 *     takes the per-frame ratio of ROI means and averages over those frames; for dF/F0 it
 *     averages the ROI mean. This needs no scalar-background workaround and is generally
 *     more accurate.
 *
 * Brightness (intensity modulation) uses the background-subtracted signal: for dR/R0 the
 * chosen source (CFP, FRET, or average); for dF/F0 the F signal.
 *
 * Requires the Subtract Background Plus plugin:
 *   https://github.com/yugo8849/subtract-background-plus
 *
 * @author Derived from Intensity Modulated Display (IMD); shared platform
 * @version 1.1.0
 */
public class Normalized_IMD implements PlugIn {

    // Modes (input arrangement)
    private static final String MODE_RATIO_TWO   = "dR/R0 - two images (FRET, CFP)";
    private static final String MODE_RATIO_MULTI = "dR/R0 - multi-channel stack";
    private static final String MODE_SINGLE      = "dF/F0 - single image";
    private static final String[] MODES = { MODE_RATIO_TWO, MODE_RATIO_MULTI, MODE_SINGLE };
    private static String modeChoice = MODE_RATIO_TWO;

    // Baseline source
    private static final String BASE_ROI   = "ROI (pre-stimulus mean)";
    private static final String BASE_FIXED = "Fixed value";
    private static final String[] BASELINE_SOURCES = { BASE_ROI, BASE_FIXED };
    private static String baselineSource = BASE_ROI;

    // Background methods (labels must match Subtract Background Plus exactly)
    private static final String[] BG_METHODS = {
        "Sliding paraboloid (separable, fast)",
        "Rolling ball (full resolution)",
        "Morphological opening (flat disk)"
    };

    // Brightness (intensity modulation) source, for ratio mode
    private static final String[] MASK_SOURCES = {"CFP (Donor)", "FRET", "Average (CFP+FRET)/2"};
    private static String maskSource = "CFP (Donor)";

    // Baseline (raw measured ROI means) - used only in Fixed-value mode
    private static double fret0 = 1000.0;
    private static double cfp0  = 1000.0;
    private static double f0    = 1000.0;

    // Pre-stimulus / baseline frame range
    private static int bgFirstFrame = 1;
    private static int bgLastFrame  = 5;

    // Background (Subtract Background Plus)
    private static boolean subtractBG = true;
    private static String bgMethod = "Sliding paraboloid (separable, fast)";
    private static double bgRadius = 50.0;
    private static double bgSmoothing = 2.0;

    // Display (delta) range
    private static double deltaMax = 2.0;
    private static double deltaMin = -0.5;

    // Intensity (brightness) range, applied to background-subtracted signal
    private static double intMax = 6000.0;
    private static double intMin = 0.0;

    // Display LUT
    private static String lutChoice = "physics";

    // Options
    private static boolean testMode = false;
    private static boolean useMultiThread = true;
    private static boolean saveParams = true;

    // Channels (multi-channel mode)
    private static int acceptorChannel = 1;
    private static int donorChannel = 2;

    // Selection indices
    private int fretIndex = 0, cfpIndex = 1, singleIndex = 0, multiIndex = 0;

    // Derived flags
    private boolean isRatio, isMultiCh, useROI;

    // Brightness-source flags (set before rendering)
    private boolean maskUseFRET, maskUseAvg;

    // Computed baseline denominators
    private double fretBg = 0, cfpBg = 0, fBg = 0;  // scalar backgrounds (Fixed mode only)
    private double r0 = 1;          // R0 used by ratio modes
    private double singleDenom = 1; // F0 denominator used by single mode

    // LUT colors
    private byte[] lutReds = new byte[256];
    private byte[] lutGreens = new byte[256];
    private byte[] lutBlues = new byte[256];

    @Override
    public void run(String arg) {
        if (!selectMode()) return;
        isRatio = !modeChoice.equals(MODE_SINGLE);
        isMultiCh = modeChoice.equals(MODE_RATIO_MULTI);
        useROI = baselineSource.equals(BASE_ROI);

        int[] ids = WindowManager.getIDList();
        int need = (isRatio && !isMultiCh) ? 2 : 1;
        if (ids == null || ids.length < need) {
            IJ.error("Normalized IMD", need == 2
                ? "Please open at least 2 images (FRET and CFP)."
                : "Please open at least 1 image.");
            return;
        }
        String[] titles = new String[ids.length];
        for (int i = 0; i < ids.length; i++) titles[i] = WindowManager.getImage(ids[i]).getTitle();

        loadParameters();
        if (!showDialog(titles)) return;

        // Resolve input images
        ImagePlus fretOrig = null, cfpOrig = null, fOrig = null, multiImp = null;
        boolean extracted = false;

        if (isRatio) {
            if (isMultiCh) {
                multiImp = WindowManager.getImage(ids[multiIndex]);
                int ch = multiImp.getNChannels();
                if (ch < 2) { IJ.error("Normalized IMD", "Selected image has only " + ch + " channel(s)."); return; }
                if (acceptorChannel < 1 || acceptorChannel > ch || donorChannel < 1 || donorChannel > ch) {
                    IJ.error("Normalized IMD", "Channel numbers must be between 1 and " + ch + "."); return;
                }
                if (acceptorChannel == donorChannel) { IJ.error("Normalized IMD", "Acceptor and donor channels must differ."); return; }
                fretOrig = extractChannel(multiImp, acceptorChannel);
                cfpOrig  = extractChannel(multiImp, donorChannel);
                extracted = true;
            } else {
                fretOrig = WindowManager.getImage(ids[fretIndex]);
                cfpOrig  = WindowManager.getImage(ids[cfpIndex]);
                if (fretOrig == cfpOrig) { IJ.error("Normalized IMD", "Please select different FRET and CFP images."); return; }
            }
            if (fretOrig.getStackSize() != cfpOrig.getStackSize()) {
                IJ.error("Normalized IMD", "FRET and CFP must have the same number of slices.");
                if (extracted) { fretOrig.close(); cfpOrig.close(); }
                return;
            }
        } else {
            fOrig = WindowManager.getImage(ids[singleIndex]);
        }

        // ROI acquisition (ROI baseline mode)
        Roi baselineRoi = null;
        if (useROI) {
            ImagePlus roiSrc = isRatio ? (isMultiCh ? multiImp : fretOrig) : fOrig;
            baselineRoi = acquireBaselineRoi(roiSrc);
            if (baselineRoi == null) { if (extracted) { fretOrig.close(); cfpOrig.close(); } return; }
        }

        if (saveParams) saveParameters();

        long t0 = System.currentTimeMillis();
        ImagePlus result = isRatio ? processRatio(fretOrig, cfpOrig, baselineRoi)
                                   : processSingle(fOrig, baselineRoi);
        long dt = System.currentTimeMillis() - t0;

        if (extracted) { fretOrig.close(); cfpOrig.close(); }

        if (result != null) {
            result.show();
            IJ.showProgress(1.0);
            IJ.showStatus("Normalized IMD complete");
            IJ.log("=== Normalized IMD complete (" + dt + " ms) ===");
            IJ.log("Output: " + result.getTitle());
        }
    }

    /** First dialog: choose mode and baseline source. */
    private boolean selectMode() {
        GenericDialog gd = new GenericDialog("Normalized IMD - Mode");
        gd.addMessage("Choose normalization mode and how the baseline (R0 / F0) is obtained.");
        gd.addChoice("Mode:", MODES, modeChoice);
        gd.addChoice("Baseline R0/F0:", BASELINE_SOURCES, baselineSource);
        gd.showDialog();
        if (gd.wasCanceled()) return false;
        modeChoice = gd.getNextChoice();
        baselineSource = gd.getNextChoice();
        return true;
    }

    /** Extract one channel (all Z and T) from a multi-channel image. */
    private ImagePlus extractChannel(ImagePlus imp, int channel) {
        int z = imp.getNSlices(), t = imp.getNFrames();
        return dupHyper(imp, channel, channel, 1, z, 1, t);
    }

    // Duplication helpers that IGNORE any ROI on the source image. The ImageJ
    // Duplicator crops to an area ROI's bounding box; we don't want that here
    // (it would crop the working images and break ROI-based R0/F0 measurement).
    // The source image's ROI is temporarily removed and then restored.
    private ImagePlus dupFull(ImagePlus imp) {
        Roi saved = imp.getRoi();
        if (saved != null) imp.deleteRoi();
        ImagePlus d = new Duplicator().run(imp);
        if (saved != null) imp.setRoi(saved);
        return d;
    }

    private ImagePlus dupRange(ImagePlus imp, int first, int last) {
        Roi saved = imp.getRoi();
        if (saved != null) imp.deleteRoi();
        ImagePlus d = new Duplicator().run(imp, first, last);
        if (saved != null) imp.setRoi(saved);
        return d;
    }

    private ImagePlus dupHyper(ImagePlus imp, int fc, int lc, int fz, int lz, int ft, int lt) {
        Roi saved = imp.getRoi();
        if (saved != null) imp.deleteRoi();
        ImagePlus d = new Duplicator().run(imp, fc, lc, fz, lz, ft, lt);
        if (saved != null) imp.setRoi(saved);
        return d;
    }

    /** Get an area ROI from the source image, prompting the user if needed. */
    private Roi acquireBaselineRoi(ImagePlus src) {
        Roi roi = src.getRoi();
        if (roi != null && roi.isArea()) return (Roi) roi.clone();
        if (src.getWindow() != null) src.getWindow().toFront();
        else src.show();
        WaitForUserDialog d = new WaitForUserDialog("Select baseline ROI",
            "Draw an area ROI over the baseline (pre-stimulus) region on:\n"
            + "  " + src.getTitle() + "\n"
            + "then click OK. (Frames " + bgFirstFrame + "-" + bgLastFrame + " will be averaged.)");
        d.show();
        if (d.escPressed()) return null;
        roi = src.getRoi();
        if (roi == null || !roi.isArea()) {
            IJ.error("Normalized IMD", "No area ROI was provided.");
            return null;
        }
        return (Roi) roi.clone();
    }

    private boolean showDialog(String[] titles) {
        String[] luts = getAvailableLUTs();
        int lutIdx = indexOf(luts, lutChoice);
        int bgIdx = indexOf(BG_METHODS, bgMethod);
        int maskIdx = indexOf(MASK_SOURCES, maskSource);

        GenericDialog gd = new GenericDialog("Normalized IMD v1.1.0");

        gd.addMessage("=== Input (" + modeChoice + ", baseline: " + baselineSource + ") ===");
        if (isRatio) {
            if (isMultiCh) {
                gd.addChoice("Multi-channel image:", titles, titles[0]);
                gd.addNumericField("Acceptor (FRET) channel:", acceptorChannel, 0);
                gd.addNumericField("Donor (CFP) channel:", donorChannel, 0);
            } else {
                gd.addChoice("FRET image:", titles, titles[0]);
                gd.addChoice("CFP (Donor) image:", titles, titles[Math.min(1, titles.length - 1)]);
            }
            if (!useROI) {
                gd.addMessage("Baseline (raw ROI means, before background subtraction):");
                gd.addNumericField("FRET0:", fret0, 2);
                gd.addNumericField("CFP0:", cfp0, 2);
            }
        } else {
            gd.addChoice("Image (F):", titles, titles[0]);
            if (!useROI) {
                gd.addMessage("Baseline (raw ROI mean, before background subtraction):");
                gd.addNumericField("F0:", f0, 2);
            }
        }

        gd.addMessage("=== Background (Subtract Background Plus) ===");
        gd.addCheckbox("Subtract background", subtractBG);
        gd.addChoice("BG method:", BG_METHODS, BG_METHODS[bgIdx]);
        gd.addNumericField("BG radius (pixels):", bgRadius, 0);
        gd.addNumericField("BG smoothing sigma (px):", bgSmoothing, 1);

        gd.addMessage("=== Pre-stimulus / baseline frames ===");
        gd.addNumericField("First frame:", bgFirstFrame, 0);
        gd.addNumericField("Last frame:", bgLastFrame, 0);
        if (useROI) gd.addMessage("R0/F0 measured from the ROI over these frames (draw ROI when prompted).");

        gd.addMessage("=== Display ===");
        gd.addNumericField("Delta max:", deltaMax, 3);
        gd.addNumericField("Delta min:", deltaMin, 3);
        gd.addChoice("LUT:", luts, luts[lutIdx]);

        gd.addMessage("=== Intensity (brightness = background-subtracted signal) ===");
        if (isRatio) gd.addChoice("Brightness source:", MASK_SOURCES, MASK_SOURCES[maskIdx]);
        gd.addNumericField("Intensity max:", intMax, 0);
        gd.addNumericField("Intensity min:", intMin, 0);

        gd.addMessage("=== Options ===");
        gd.addCheckbox("Test mode (first frame only)", testMode);
        gd.addCheckbox("Multi-threaded processing", useMultiThread);
        gd.addCheckbox("Save parameters", saveParams);

        gd.showDialog();
        if (gd.wasCanceled()) return false;

        // Read in the same order fields were added
        if (isRatio) {
            if (isMultiCh) {
                multiIndex = gd.getNextChoiceIndex();
                acceptorChannel = (int) gd.getNextNumber();
                donorChannel = (int) gd.getNextNumber();
            } else {
                fretIndex = gd.getNextChoiceIndex();
                cfpIndex = gd.getNextChoiceIndex();
            }
            if (!useROI) { fret0 = gd.getNextNumber(); cfp0 = gd.getNextNumber(); }
        } else {
            singleIndex = gd.getNextChoiceIndex();
            if (!useROI) f0 = gd.getNextNumber();
        }

        subtractBG = gd.getNextBoolean();
        bgMethod = gd.getNextChoice();
        bgRadius = gd.getNextNumber();
        bgSmoothing = gd.getNextNumber();

        bgFirstFrame = (int) gd.getNextNumber();
        bgLastFrame = (int) gd.getNextNumber();

        deltaMax = gd.getNextNumber();
        deltaMin = gd.getNextNumber();
        lutChoice = gd.getNextChoice();

        if (isRatio) maskSource = gd.getNextChoice();
        intMax = gd.getNextNumber();
        intMin = gd.getNextNumber();

        testMode = gd.getNextBoolean();
        useMultiThread = gd.getNextBoolean();
        saveParams = gd.getNextBoolean();
        return true;
    }

    // ----------------------------------------------------------------
    // Processing
    // ----------------------------------------------------------------

    private ImagePlus processRatio(ImagePlus fretOrig, ImagePlus cfpOrig, Roi roi) {
        int w = fretOrig.getWidth(), h = fretOrig.getHeight();
        int nAll = fretOrig.getStackSize();

        IJ.log("\n=== Normalized IMD (dR/R0, baseline: " + baselineSource + ") ===");

        if (useROI) {
            r0 = measureR0FromRoi(fretOrig, cfpOrig, roi);
            if (Double.isNaN(r0) || r0 <= 0) {
                IJ.error("Normalized IMD", "Could not measure a valid R0 from the ROI (R0=" + IJ.d2s(r0, 5) + ").");
                return null;
            }
        } else {
            if (subtractBG) {
                fretBg = computeScalarBackground(fretOrig);
                cfpBg  = computeScalarBackground(cfpOrig);
            } else { fretBg = 0; cfpBg = 0; IJ.log("Background subtraction: Disabled (bg = 0)"); }
            double cfp0c = cfp0 - cfpBg, fret0c = fret0 - fretBg;
            if (cfp0c <= 0) {
                IJ.error("Normalized IMD", "CFP0 - CFP_bg must be > 0.\nCFP0=" + cfp0 + ", CFP_bg=" + IJ.d2s(cfpBg, 2));
                return null;
            }
            if (fret0c <= 0) IJ.log("Warning: FRET0 - FRET_bg <= 0; R0 will be non-positive.");
            r0 = fret0c / cfp0c;
            IJ.log("FRET_bg=" + IJ.d2s(fretBg, 3) + "  CFP_bg=" + IJ.d2s(cfpBg, 3));
        }
        IJ.log("R0 = " + IJ.d2s(r0, 5) + "   Delta range: " + deltaMin + " to " + deltaMax + "   LUT: " + lutChoice);

        // Working copies for rendering (test mode -> first frame)
        ImagePlus fretImp, cfpImp;
        int nSlices = nAll;
        if (testMode && nAll > 1) {
            IJ.log("*** TEST MODE: first frame only ***");
            fretImp = dupHyper(fretOrig, 1, 1, 1, 1, 1, 1);
            cfpImp  = dupHyper(cfpOrig, 1, 1, 1, 1, 1, 1);
            nSlices = 1;
        } else {
            fretImp = dupFull(fretOrig);
            cfpImp  = dupFull(cfpOrig);
        }
        if (subtractBG) { subtractBackgroundSpatial(fretImp); subtractBackgroundSpatial(cfpImp); }

        maskUseFRET = maskSource.equals("FRET");
        maskUseAvg = maskSource.equals("Average (CFP+FRET)/2");
        IJ.log("Brightness source: " + maskSource);

        if (!loadLUTColors(lutChoice)) { IJ.log("Warning: LUT '" + lutChoice + "' not found, using Fire"); loadLUTColors("Fire"); }

        ImageStack out = render(fretImp, cfpImp, w, h, nSlices);
        ImagePlus result = new ImagePlus(titleFor("dRR0"), out);
        result.setCalibration(fretOrig.getCalibration().copy());
        fretImp.close(); cfpImp.close();
        return result;
    }

    private ImagePlus processSingle(ImagePlus fOrig, Roi roi) {
        int w = fOrig.getWidth(), h = fOrig.getHeight();
        int nAll = fOrig.getStackSize();

        IJ.log("\n=== Normalized IMD (dF/F0, baseline: " + baselineSource + ") ===");

        if (useROI) {
            singleDenom = measureF0FromRoi(fOrig, roi);
            if (Double.isNaN(singleDenom) || singleDenom <= 0) {
                IJ.error("Normalized IMD", "Could not measure a valid F0 from the ROI (F0=" + IJ.d2s(singleDenom, 3) + ").");
                return null;
            }
        } else {
            if (subtractBG) fBg = computeScalarBackground(fOrig);
            else { fBg = 0; IJ.log("Background subtraction: Disabled (bg = 0)"); }
            double f0c = f0 - fBg;
            if (f0c <= 0) {
                IJ.error("Normalized IMD", "F0 - F_bg must be > 0.\nF0=" + f0 + ", F_bg=" + IJ.d2s(fBg, 2));
                return null;
            }
            singleDenom = f0c;
            IJ.log("F_bg=" + IJ.d2s(fBg, 3));
        }
        IJ.log("F0 (denominator) = " + IJ.d2s(singleDenom, 3) + "   Delta range: " + deltaMin + " to " + deltaMax + "   LUT: " + lutChoice);

        ImagePlus fImp;
        int nSlices = nAll;
        if (testMode && nAll > 1) {
            IJ.log("*** TEST MODE: first frame only ***");
            fImp = dupHyper(fOrig, 1, 1, 1, 1, 1, 1);
            nSlices = 1;
        } else {
            fImp = dupFull(fOrig);
        }
        if (subtractBG) subtractBackgroundSpatial(fImp);

        if (!loadLUTColors(lutChoice)) { IJ.log("Warning: LUT '" + lutChoice + "' not found, using Fire"); loadLUTColors("Fire"); }

        ImageStack out = render(fImp, null, w, h, nSlices);
        ImagePlus result = new ImagePlus(titleFor("dFF0"), out);
        result.setCalibration(fOrig.getCalibration().copy());
        fImp.close();
        return result;
    }

    /** Measure R0 from a ROI: per-frame ratio of ROI means on background-subtracted baseline frames, averaged. */
    private double measureR0FromRoi(ImagePlus fretOrig, ImagePlus cfpOrig, Roi roi) {
        int n = fretOrig.getStackSize();
        int first = Math.max(1, Math.min(bgFirstFrame, n));
        int last  = Math.max(first, Math.min(bgLastFrame, n));
        ImagePlus fb = dupRange(fretOrig, first, last);
        ImagePlus cb = dupRange(cfpOrig, first, last);
        if (subtractBG) { subtractBackgroundSpatial(fb); subtractBackgroundSpatial(cb); }
        int n2 = fb.getStackSize();
        double sumR = 0; int cnt = 0;
        for (int s = 1; s <= n2; s++) {
            fb.setSlice(s); cb.setSlice(s);
            fb.setRoi((Roi) roi.clone()); cb.setRoi((Roi) roi.clone());
            double fm = fb.getStatistics(Measurements.MEAN).mean;
            double cm = cb.getStatistics(Measurements.MEAN).mean;
            if (cm != 0) { sumR += fm / cm; cnt++; }
        }
        fb.close(); cb.close();
        double r = cnt > 0 ? sumR / cnt : Double.NaN;
        IJ.log("R0 from ROI (mean of FRET_sub/CFP_sub over frames " + first + "-" + last + ") = " + IJ.d2s(r, 5));
        return r;
    }

    /** Measure F0 from a ROI: ROI mean on background-subtracted baseline frames, averaged. */
    private double measureF0FromRoi(ImagePlus fOrig, Roi roi) {
        int n = fOrig.getStackSize();
        int first = Math.max(1, Math.min(bgFirstFrame, n));
        int last  = Math.max(first, Math.min(bgLastFrame, n));
        ImagePlus fb = dupRange(fOrig, first, last);
        if (subtractBG) subtractBackgroundSpatial(fb);
        int n2 = fb.getStackSize();
        double sum = 0; int cnt = 0;
        for (int s = 1; s <= n2; s++) {
            fb.setSlice(s);
            fb.setRoi((Roi) roi.clone());
            sum += fb.getStatistics(Measurements.MEAN).mean;
            cnt++;
        }
        fb.close();
        double f = cnt > 0 ? sum / cnt : Double.NaN;
        IJ.log("F0 from ROI (mean of F_sub over frames " + first + "-" + last + ") = " + IJ.d2s(f, 3));
        return f;
    }

    /**
     * Spatially subtract background with Subtract Background Plus.
     * Command name has NO "..." suffix; for stacks " stack" enables all-slice processing.
     */
    private void subtractBackgroundSpatial(ImagePlus imp) {
        int n = imp.getStackSize();
        String stackOpt = n > 1 ? " stack" : "";
        String opt = "method=[" + bgMethod + "] radius=" + bgRadius
                   + " smoothing=" + bgSmoothing + " background=0 shrink=1" + stackOpt;
        IJ.run(imp, "Subtract Background Plus", opt);
    }

    /**
     * Scalar background for one channel (Fixed-value baseline only): duplicate baseline
     * frames, run Subtract Background Plus "Create background", average over those frames.
     */
    private double computeScalarBackground(ImagePlus orig) {
        int n = orig.getStackSize();
        int first = Math.max(1, Math.min(bgFirstFrame, n));
        int last  = Math.max(first, Math.min(bgLastFrame, n));
        ImagePlus sub = dupRange(orig, first, last);
        int n2 = sub.getStackSize();
        String stackOpt = n2 > 1 ? " stack" : "";
        String opt = "method=[" + bgMethod + "] radius=" + bgRadius
                   + " smoothing=" + bgSmoothing + " background=0 shrink=1 create" + stackOpt;
        IJ.run(sub, "Subtract Background Plus", opt);
        double sum = 0; long count = 0;
        for (int s = 1; s <= n2; s++) {
            sub.setSlice(s);
            float[] px = getFloatPixels(sub.getProcessor());
            for (int i = 0; i < px.length; i++) { sum += px[i]; count++; }
        }
        sub.close();
        double bg = count > 0 ? sum / count : 0;
        IJ.log("Background scalar (frames " + first + "-" + last + ") mean = " + IJ.d2s(bg, 3));
        return bg;
    }

    /** Render all slices to RGB, threaded or single-threaded. */
    private ImageStack render(ImagePlus a, ImagePlus b, int w, int h, int nSlices) {
        final float fDeltaMin = (float) deltaMin;
        float dRange = (float) (deltaMax - deltaMin);
        if (dRange == 0) dRange = 1;
        final float fDeltaRange = dRange;
        final float fIntMin = (float) intMin;
        float iRange = (float) (intMax - intMin);
        if (iRange == 0) iRange = 1;
        final float fIntRange = iRange;

        ImageStack out = new ImageStack(w, h);

        if (useMultiThread && nSlices > 1) {
            final float[][] aD = new float[nSlices][];
            final float[][] bD = (b != null) ? new float[nSlices][] : null;
            for (int s = 0; s < nSlices; s++) {
                a.setSlice(s + 1);
                aD[s] = getFloatPixels(a.getProcessor());
                if (b != null) { b.setSlice(s + 1); bD[s] = getFloatPixels(b.getProcessor()); }
            }
            final int[][] res = new int[nSlices][];
            int nT = Runtime.getRuntime().availableProcessors();
            ExecutorService ex = Executors.newFixedThreadPool(nT);
            AtomicInteger prog = new AtomicInteger(0);
            IJ.log("Using " + nT + " threads");
            for (int s = 0; s < nSlices; s++) {
                final int slice = s;
                ex.submit(() -> {
                    res[slice] = processPixels(aD[slice], (bD != null ? bD[slice] : null),
                                               w * h, fDeltaMin, fDeltaRange, fIntMin, fIntRange);
                    int d = prog.incrementAndGet();
                    IJ.showProgress(d, nSlices);
                    IJ.showStatus("Normalized IMD: " + d + "/" + nSlices);
                });
            }
            ex.shutdown();
            try { ex.awaitTermination(1, TimeUnit.HOURS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            for (int s = 0; s < nSlices; s++) out.addSlice(new ColorProcessor(w, h, res[s]));
        } else {
            for (int s = 1; s <= nSlices; s++) {
                a.setSlice(s);
                float[] aP = getFloatPixels(a.getProcessor());
                float[] bP = null;
                if (b != null) { b.setSlice(s); bP = getFloatPixels(b.getProcessor()); }
                int[] rgb = processPixels(aP, bP, w * h, fDeltaMin, fDeltaRange, fIntMin, fIntRange);
                out.addSlice(new ColorProcessor(w, h, rgb));
                if (nSlices > 1) { IJ.showProgress(s, nSlices); IJ.showStatus("Normalized IMD: " + s + "/" + nSlices); }
            }
        }
        return out;
    }

    /**
     * Core per-pixel processing. Input arrays are background-subtracted (spatially) when
     * background subtraction is enabled.
     * Ratio mode: a = FRET_sub, b = CFP_sub. Single mode: a = F_sub, b = null.
     */
    private int[] processPixels(float[] aPix, float[] bPix, int size,
                                float fDeltaMin, float fDeltaRange, float fIntMin, float fIntRange) {
        int[] rgb = new int[size];
        final byte[] reds = lutReds, greens = lutGreens, blues = lutBlues;
        final float r0F = (float) r0;
        final float denomF = (float) singleDenom;
        final boolean ratio = isRatio;
        final boolean useFRET = maskUseFRET, useAvg = maskUseAvg;

        for (int i = 0; i < size; i++) {
            float delta, bright;
            if (ratio) {
                float fretSub = aPix[i];
                float cfpSub  = bPix[i];
                if (cfpSub > 0) {
                    float R = fretSub / cfpSub;
                    delta = R / r0F - 1f;
                } else {
                    delta = fDeltaMin;
                }
                if (useFRET) bright = fretSub;
                else if (useAvg) bright = 0.5f * (fretSub + cfpSub);
                else bright = cfpSub;
            } else {
                float fSub = aPix[i];
                delta = fSub / denomF - 1f;
                bright = fSub;
            }
            if (Float.isNaN(delta) || Float.isInfinite(delta)) delta = fDeltaMin;

            float normD = (delta - fDeltaMin) / fDeltaRange;
            if (normD < 0) normD = 0; else if (normD > 1) normD = 1;
            int idx = (int) (normD * 255);
            if (idx < 0) idx = 0; else if (idx > 255) idx = 255;

            float mask = (bright - fIntMin) / fIntRange;
            if (mask < 0) mask = 0; else if (mask > 1) mask = 1;

            int r = (int) ((reds[idx] & 0xff) * mask);
            int g = (int) ((greens[idx] & 0xff) * mask);
            int bl = (int) ((blues[idx] & 0xff) * mask);
            if (r > 255) r = 255;
            if (g > 255) g = 255;
            if (bl > 255) bl = 255;
            rgb[i] = (r << 16) | (g << 8) | bl;
        }
        return rgb;
    }

    private String titleFor(String tag) {
        return "NIMD-" + tag + "-max" + deltaMax + "-min" + deltaMin + "-" + lutChoice;
    }

    private int indexOf(String[] arr, String val) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(val)) return i;
        return 0;
    }

    // ----------------------------------------------------------------
    // Helpers reused from the IMD platform
    // ----------------------------------------------------------------

    /** Get pixels as float array (handles 8, 16, 32-bit). */
    private float[] getFloatPixels(ImageProcessor ip) {
        int size = ip.getWidth() * ip.getHeight();
        float[] result = new float[size];
        if (ip instanceof FloatProcessor) {
            float[] src = (float[]) ip.getPixels();
            System.arraycopy(src, 0, result, 0, size);
        } else if (ip instanceof ShortProcessor) {
            short[] pixels = (short[]) ip.getPixels();
            for (int i = 0; i < size; i++) result[i] = pixels[i] & 0xffff;
        } else if (ip instanceof ByteProcessor) {
            byte[] pixels = (byte[]) ip.getPixels();
            for (int i = 0; i < size; i++) result[i] = pixels[i] & 0xff;
        } else {
            for (int i = 0; i < size; i++) result[i] = ip.getf(i);
        }
        return result;
    }

    /** Load LUT colors into arrays. */
    private boolean loadLUTColors(String lutName) {
        try {
            ByteProcessor bp = new ByteProcessor(256, 1);
            for (int i = 0; i < 256; i++) bp.set(i, 0, i);
            ImagePlus tempImp = new ImagePlus("temp", bp);
            IJ.run(tempImp, lutName, "");
            LUT lut = tempImp.getProcessor().getLut();
            if (lut != null) {
                lut.getReds(lutReds);
                lut.getGreens(lutGreens);
                lut.getBlues(lutBlues);
                tempImp.close();
                return true;
            }
            tempImp.close();
            return false;
        } catch (Exception e) {
            IJ.log("Error loading LUT: " + e.getMessage());
            return false;
        }
    }

    /** Build the list of available LUTs (built-in + custom in luts/). */
    private String[] getAvailableLUTs() {
        TreeSet<String> luts = new TreeSet<>();
        String[] builtIn = {
            "Grays", "Fire", "Ice", "Spectrum", "Red", "Green", "Blue",
            "Cyan", "Magenta", "Yellow", "Red/Green", "physics", "Jet",
            "Thermal", "Rainbow RGB", "Red Hot", "Green Fire Blue",
            "16 colors", "5 ramps", "6 shades"
        };
        Collections.addAll(luts, builtIn);
        String ijDir = IJ.getDirectory("imagej");
        if (ijDir != null) {
            String lutDir = ijDir + "luts" + File.separator;
            File folder = new File(lutDir);
            if (folder.exists() && folder.isDirectory()) {
                File[] files = folder.listFiles((d, nm) -> nm.endsWith(".lut"));
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName();
                        luts.add(name.substring(0, name.length() - 4));
                    }
                }
            }
        }
        return luts.toArray(new String[0]);
    }

    // ----------------------------------------------------------------
    // Parameter persistence (separate file from IMD)
    // ----------------------------------------------------------------

    private void loadParameters() {
        String ijDir = IJ.getDirectory("imagej");
        if (ijDir == null) return;
        File f = new File(ijDir + "NIMD_parameters.txt");
        if (!f.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("mode=")) modeChoice = line.substring(5).trim();
                else if (line.startsWith("baseline_source=")) baselineSource = line.substring(16).trim();
                else if (line.startsWith("mask_source=")) maskSource = line.substring(12).trim();
                else if (line.startsWith("fret0=")) fret0 = Double.parseDouble(line.substring(6));
                else if (line.startsWith("cfp0=")) cfp0 = Double.parseDouble(line.substring(5));
                else if (line.startsWith("f0=")) f0 = Double.parseDouble(line.substring(3));
                else if (line.startsWith("bg_first=")) bgFirstFrame = Integer.parseInt(line.substring(9).trim());
                else if (line.startsWith("bg_last=")) bgLastFrame = Integer.parseInt(line.substring(8).trim());
                else if (line.startsWith("subtract_bg=")) subtractBG = Boolean.parseBoolean(line.substring(12).trim());
                else if (line.startsWith("bg_method=")) bgMethod = line.substring(10).trim();
                else if (line.startsWith("bg_radius=")) bgRadius = Double.parseDouble(line.substring(10));
                else if (line.startsWith("bg_smoothing=")) bgSmoothing = Double.parseDouble(line.substring(13));
                else if (line.startsWith("delta_max=")) deltaMax = Double.parseDouble(line.substring(10));
                else if (line.startsWith("delta_min=")) deltaMin = Double.parseDouble(line.substring(10));
                else if (line.startsWith("int_max=")) intMax = Double.parseDouble(line.substring(8));
                else if (line.startsWith("int_min=")) intMin = Double.parseDouble(line.substring(8));
                else if (line.startsWith("lut=")) lutChoice = line.substring(4).trim();
                else if (line.startsWith("acceptor_channel=")) acceptorChannel = Integer.parseInt(line.substring(17).trim());
                else if (line.startsWith("donor_channel=")) donorChannel = Integer.parseInt(line.substring(14).trim());
            }
        } catch (Exception e) {
            // ignore, use defaults
        }
    }

    private void saveParameters() {
        String ijDir = IJ.getDirectory("imagej");
        if (ijDir == null) return;
        String paramFile = ijDir + "NIMD_parameters.txt";
        try (PrintWriter w = new PrintWriter(new FileWriter(paramFile))) {
            w.println("mode=" + modeChoice);
            w.println("baseline_source=" + baselineSource);
            w.println("mask_source=" + maskSource);
            w.println("fret0=" + fret0);
            w.println("cfp0=" + cfp0);
            w.println("f0=" + f0);
            w.println("bg_first=" + bgFirstFrame);
            w.println("bg_last=" + bgLastFrame);
            w.println("subtract_bg=" + subtractBG);
            w.println("bg_method=" + bgMethod);
            w.println("bg_radius=" + bgRadius);
            w.println("bg_smoothing=" + bgSmoothing);
            w.println("delta_max=" + deltaMax);
            w.println("delta_min=" + deltaMin);
            w.println("int_max=" + intMax);
            w.println("int_min=" + intMin);
            w.println("lut=" + lutChoice);
            w.println("acceptor_channel=" + acceptorChannel);
            w.println("donor_channel=" + donorChannel);
            IJ.log("Parameters saved to: " + paramFile);
        } catch (Exception e) {
            IJ.log("Warning: Could not save parameters");
        }
    }
}
