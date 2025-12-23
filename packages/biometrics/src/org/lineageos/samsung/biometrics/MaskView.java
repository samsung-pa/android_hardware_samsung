package org.lineageos.samsung.biometrics;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

public final class MaskView implements DisplayBrightnessMonitor.OnBrightnessListener {
    private final Context mContext;
    private final WindowManager mWm;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    public final SemDisplaySolution mDisplaySolutionManager;
    public final DisplayBrightnessMonitor mDisplayBrightnessMonitor;

    private View mRoot;
    private boolean mAdded;

    public MaskView(Context context, DisplayBrightnessMonitor monitor, SemDisplaySolution dsm) {
        mContext = context;
        mWm = context.getSystemService(WindowManager.class);
        mDisplayBrightnessMonitor = monitor;
        mDisplaySolutionManager = dsm;
    }

    public void start() {
        mDisplayBrightnessMonitor.registerListener(this);
    }

    public void stop() {
        mDisplayBrightnessMonitor.unregisterListener(this);
    }

    @Override
    public void onBrightnessChanged(int brightness) {
        updateBackgroundColor(brightness);
    }

    public void show() {
        mMain.post(() -> {
            if (mAdded) return;
            ensureView();
            updateBackgroundColor(-1);
            mWm.addView(mRoot, buildLayoutParams());
            mAdded = true;
        });
    }

    public void hide() {
        mMain.post(() -> {
            if (!mAdded) return;
            try {
                mWm.removeViewImmediate(mRoot);
            } finally {
                mAdded = false;
            }
        });
    }

    public boolean isShowing() {
        return mAdded;
    }

    private void ensureView() {
        if (mRoot != null) return;

        // Programmatic layout (you can replace with LayoutInflater.inflate(R.layout....))
        FrameLayout root = new FrameLayout(mContext);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        mRoot = root;
    }

    private WindowManager.LayoutParams buildLayoutParams() {
        Point size = new Point();
        Display d = mContext.getDisplay();
        if (d != null) {
            d.getRealSize(size);
        } else {
            size.x = WindowManager.LayoutParams.MATCH_PARENT;
            size.y = WindowManager.LayoutParams.MATCH_PARENT;
        }

        final int flags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

        // Requires privileged context (SystemUI / platform-signed privapp with proper perms).
        int type = WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                size.x,
                size.y,
                type,
                flags,
                PixelFormat.TRANSLUCENT
        );

        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0);
        lp.setTitle("Dim Layer for UDFPS");

        return lp;
    }

public void updateBackgroundColor(int displayBrightness) {
        if (displayBrightness == -1) {
            displayBrightness = mDisplayBrightnessMonitor.getCurrentBrightness();
        }
        if (displayBrightness < 0) displayBrightness = 127;

        float currentBacklight = displayBrightness;

        float target = 525.0f; // (make sure units match your getAlphaMaskLevel implementation)
        float alpha = mDisplaySolutionManager.getAlphaMaskLevel(currentBacklight, target, 1.0f);

        alpha = Math.min(0.93f, alpha);

        int maskAlpha = (int) (255f * alpha);
        int argb = (maskAlpha << 24); // black with alpha

        // Apply to the view that exists in this overlay
        if (mRoot != null) {
                mRoot.setBackgroundColor(argb);
        }
}

}
