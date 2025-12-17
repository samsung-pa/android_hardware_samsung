package com.samsung.android.displaysolution;

import android.content.Context;
import android.content.res.Resources;
import android.util.Slog;

public final class SemDisplaySolutionManagerService extends ISemDisplaySolutionManager.Stub {
    private static final String TAG = "SemDisplaySolutionManagerService";

    private final int[] mBacklight;   // 0..486
    private final float[] mNits;      // same size as backlight
    private final float mGamma;       // pick a constant or resource-backed

    public SemDisplaySolutionManagerService(Context context) {
        final Resources res = context.getResources();

        mBacklight = res.getIntArray(com.android.internal.R.array.config_screenBrightnessBacklight);
        mNits = loadFloatArray(res, com.android.internal.R.array.config_screenBrightnessNits);

        if (mBacklight.length != mNits.length) {
            throw new IllegalStateException("Brightness arrays mismatch: backlight="
                    + mBacklight.length + " nits=" + mNits.length);
        }

        // Default gamma value of SemDisplaySolutionManagerService.
        // Seems generic.
        mGamma = 2.2f;

        Slog.d(TAG, "FP dimming: loaded size=" + mNits.length + " gamma=" + mGamma);
    }

    /**
     * brightnessNits is an integer in the RE code.
     * We match by threshold: first nits >= brightnessNits.
     * Return the backlight value at the same index.
     */
    public float getFingerPrintBacklightValue(int brightnessNits) {
        final float n = (float) brightnessNits;

        for (int i = 0; i < mNits.length; i++) {
            if (n <= mNits[i]) {
                final int bl = mBacklight[i];
                Slog.d(TAG, "getFingerPrintBacklightValue(): nits=" + brightnessNits
                        + " <= " + mNits[i] + " -> backlight=" + bl);
                return (float) bl;
            }
        }
        return -1.0f;
    }

    /**
     * Samsung RE formula (fixing missing r7 => gamma):
     * alpha = 1 - pow((currentNits * br_ctrl) / targetNits, 1/gamma)
     *
     * Note: Your RE takes float indices for currentPlatformBrightnessValue / FingerPrintPlatformValue.
     * That implies caller passes "brightness bucket index", not nits itself.
     */
    public float getAlphaMaskLevel(float currentIdxF, float targetIdxF, float brCtrl) {
        final int cur = (int) currentIdxF;
        final int tgt = (int) targetIdxF;

        final float currentNits = (cur >= 0 && cur < mNits.length) ? mNits[cur] : -1f;
        final float targetNits  = (tgt >= 0 && tgt < mNits.length) ? mNits[tgt] : -1f;

        Slog.d(TAG, "getAlphaMaskLevel(): curIdx=" + cur + " tgtIdx=" + tgt
                + " br_ctrl=" + brCtrl + " currentNits=" + currentNits
                + " targetNits=" + targetNits + " gamma=" + mGamma);

        if (currentNits <= 0f || targetNits <= 0f || brCtrl <= 0f || mGamma <= 0f) {
            return 0f;
        }

        final double ratio = (currentNits * brCtrl) / targetNits;
        final double pow = Math.pow(ratio, 1.0d / mGamma);
        return clamp01((float) (1.0d - pow));
    }

    // ---- helpers ----

    private static float[] loadFloatArray(Resources res, int arrayResId) {
        // config_screenBrightnessNits is defined using <array>, so we read as String[] then parse.
        final String[] strs = res.getStringArray(arrayResId);
        final float[] out = new float[strs.length];
        for (int i = 0; i < strs.length; i++) {
            out[i] = Float.parseFloat(strs[i].trim());
        }
        return out;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
