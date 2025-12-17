package com.android.server.biometrics.sensors.fingerprint;

import android.R;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerInternal;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import com.android.server.LocalServices;
import com.android.server.biometrics.SemBiometricFeature;
import com.android.server.biometrics.SemBiometricSysUiManager;
import com.android.server.biometrics.Utils;
import com.android.server.wm.PackageSettingsManager;
import com.samsung.android.displaysolution.SemDisplaySolutionManager;
import com.samsung.android.lib.dexcontrol.utils.GsimcLogger;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;

/* loaded from: classes.dex */
public class SemInDisplaySensorImpl {
    private static final String TAG = "FingerprintService";

    private static final String FOD_POS_PATH = "/sys/class/fingerprint/fingerprint/position";
    private static final String FOD_TSP_PATH = "/sys/class/sec/tsp/cmd";

    private static final String HW_LIGHT_SOURCE_PATH = "/sys/class/lcd/panel/fp_green_circle";
    protected static final String HW_LIGHT_GREEN_CIRCLE_DRAW = "1";
    protected static final String HW_LIGHT_GREEN_CIRCLE_HIDE = "0";

    // Calibration types
    private static final int OPTICAL_CALIBRATION_NORMAL = 1;
    private static final int OPTICAL_CALIBRATION_LOW_BRIGHTNESS = 2;

    public static final int TIME_DOZE_RESET = 10000;

    // TSP commands
    protected static final String TSP_FOD_DISABLE = "fod_enable,0";
    protected static final String TSP_FOD_ENABLE_0 = "fod_enable,1,1,0";
    protected static final String TSP_FOD_ENABLE_50 = "fod_enable,1,0,0";
    protected static final String TSP_FOD_STRICT_ENABLE_0 = "fod_enable,1,1,1";
    protected static final String TSP_FOD_STRICT_ENABLE_50 = "fod_enable,1,0,1";
    protected static final String TSP_FOD_STRICT_MODE = "fod_strict";

    protected static final String TSP_FOD_LP_ENABLE = "fod_lp_mode,1";
    protected static final String TSP_FOD_LP_DISABLE = "fod_lp_mode,0";

    protected static final String TSP_FOD_TEMPERATURE = "set_temperature,1";

    private static final boolean DEBUG = Utils.DEBUG;
    private static final boolean IS_OPTICAL = SemBiometricFeature.FP_FEATURE_SENSOR_IS_OPTICAL;

    private static final Object sInstanceLock = new Object();
    private static SemInDisplaySensorImpl sInstance;

    private static boolean mIsStrictMode = false;
    private static String mLatestTspMode = "";
    private static boolean mIsTspLpMode;

    private BurnInHelper mBurnInHelper;                 // Samsung class (external)
    private FodLimitationListener mFodLimitationListener; // Samsung class (external)
    private final Handler mHandler;
    private final OpticalSensorHelper mOpticalImpl;

    // Sensor config strings (from /sys/class/fingerprint/fingerprint/position)
    private String mSemSensorAreaWidth = "9";
    private String mSemSensorAreaHeight = GsimcLogger.GSIM_STATUS_LOG_TA_POWER_LEVEL_4; // "4"
    private String mSemSensorMarginBottom = "13.77";
    private String mSemSensorMarginLeft = "0";
    private String mSemSensorImageSize = "13.00";
    private String mSemSensorActiveArea = "14.80";
    private String mSemSensorDraggingArea = "5.00";

    private String mLatestHwLightMode = "";
    private final Context mContext = SemFingerprintServiceExtImpl.getContext(); // Samsung class (external)

    private SemInDisplaySensorImpl() {
        mHandler = Utils.BioFpMainThread.getHandler();
        mFodLimitationListener = new FodLimitationListener(mContext, mHandler);
        mOpticalImpl = IS_OPTICAL ? new OpticalSensorHelper() : null;
    }

    public static SemInDisplaySensorImpl getInstance() {
        synchronized (sInstanceLock) {
            if (sInstance == null) {
                sInstance = new SemInDisplaySensorImpl();
            }
            return sInstance;
        }
    }

    public static OpticalSensorHelper getOpticalSensorHelper() {
        return getInstance().mOpticalImpl;
    }

    public void handleScreenOnOffBroadcast(boolean on, boolean hasClient) {
        if (IS_OPTICAL) return;

        if (on) {
            setTspMode(TSP_FOD_ENABLE_0);
        } else {
            setTspMode(hasClient ? TSP_FOD_ENABLE_50 : TSP_FOD_DISABLE);
        }
    }

    public void notifyFingerStartEventToTSP() {
        Utils.BioBgThread.getHandler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Utils.writeFile(new File(FOD_TSP_PATH), TSP_FOD_TEMPERATURE.getBytes());
                } catch (IOException e) {
                    Log.w(TAG, "notifyFingerStartEventToTSP: ", e);
                }
            }
        });
    }

    void observeFodLimitationListener(boolean on) {
        if (mFodLimitationListener != null) {
            mFodLimitationListener.observe(on);
        }
    }

    boolean isWirelessPowerLimitationRunning() {
        return mFodLimitationListener != null && mFodLimitationListener.isWirelessPowerLimitationRunning();
    }

    void handleAuthenticated(SemFingerprintClientExtImpl ce, boolean authenticated) {
        if (SemBiometricFeature.FP_FEATURE_SENSOR_LIMITATION_WIRELESS_CHARGER
                && mFodLimitationListener != null
                && !ce.isKeyguard()) {
            mFodLimitationListener.onAuthenticatedResult(authenticated);
        }
    }

    public void setFodStrictMode(boolean isStrictMode) {
        mIsStrictMode = isStrictMode;
        setTspMode(TSP_FOD_STRICT_MODE);
    }

    public static void setTspLpMode(final boolean on) {
        Utils.BioBgThread.getHandler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    setTspLpModeInternal(on);
                } catch (IOException e) {
                    Log.w(TAG, "setTspLpMode: ", e);
                }
            }
        });
    }

    private static void setTspLpModeInternal(boolean on) throws IOException {
        if (mIsTspLpMode == on) return;

        mIsTspLpMode = on;
        final String lpCmd = on ? TSP_FOD_LP_ENABLE : TSP_FOD_LP_DISABLE;
        Utils.writeFile(new File(FOD_TSP_PATH), lpCmd.getBytes());

        if (Utils.DEBUG || Utils.isDebugLevelMid()) {
            Log.i(TAG, "setTspLpMode: [" + lpCmd + "] done");
        }
    }

    public static void setTspMode(final String mode) {
        Utils.BioBgThread.getHandler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    setTspModeInternal(mode);
                } catch (IOException e) {
                    Log.w(TAG, "setTspMode: ", e);
                }
            }
        });
    }

    private static void setTspModeInternal(String mode) throws IOException {
        String currentCmd = mode;

        if (TSP_FOD_STRICT_MODE.equals(mode)) {
            if (mIsStrictMode) {
                if (TSP_FOD_ENABLE_0.equals(mLatestTspMode)) {
                    currentCmd = TSP_FOD_STRICT_ENABLE_0;
                } else if (TSP_FOD_ENABLE_50.equals(mLatestTspMode)) {
                    currentCmd = TSP_FOD_STRICT_ENABLE_50;
                }
            } else {
                if (TSP_FOD_STRICT_ENABLE_0.equals(mLatestTspMode)) {
                    currentCmd = TSP_FOD_ENABLE_0;
                } else if (TSP_FOD_STRICT_ENABLE_50.equals(mLatestTspMode)) {
                    currentCmd = TSP_FOD_ENABLE_50;
                }
            }
        } else if (mIsStrictMode) {
            if (TSP_FOD_ENABLE_0.equals(currentCmd)) {
                currentCmd = TSP_FOD_STRICT_ENABLE_0;
            } else if (TSP_FOD_ENABLE_50.equals(currentCmd)) {
                currentCmd = TSP_FOD_STRICT_ENABLE_50;
            }
        }

        if (!mLatestTspMode.equals(currentCmd)) {
            mLatestTspMode = currentCmd;
            Utils.writeFile(new File(FOD_TSP_PATH), currentCmd.getBytes());
            Log.i(TAG, "setTspMode: [" + mLatestTspMode + "] done");
        }
    }

    public void setHwLightMode(String mode) {
        if (!mLatestHwLightMode.equals(mode)) {
            Utils.writeFile(new File(HW_LIGHT_SOURCE_PATH), mode.getBytes());
            if (Utils.DEBUG || Utils.isDebugLevelMid()) {
                Log.i(TAG, "setHwLightMode: [" + mode + "] done");
            }
            mLatestHwLightMode = mode;
        }
    }

    public void readSensorPosInfo() {
        final File file = new File(FOD_POS_PATH);
        final byte[] buffer = Utils.readFile(file);
        if (buffer == null) return;

        try {
            final String sensorConfig = new String(buffer).trim();
            final String[] values = sensorConfig.split("\\,");

            // Samsung checks ">= 8" but reads index 8 too; make it safe.
            if (values.length > 0) mSemSensorMarginBottom = values[0];
            if (values.length > 1) mSemSensorMarginLeft = values[1];
            if (values.length > 2) mSemSensorAreaWidth = values[2];
            if (values.length > 3) mSemSensorAreaHeight = values[3];
            if (values.length > 5) mSemSensorActiveArea = values[5];
            if (values.length > 7) mSemSensorImageSize = values[7];
            if (values.length > 8) mSemSensorDraggingArea = values[8];

            if (DEBUG && values.length > 8) {
                Log.i(TAG, "readSensorConfig: " + values[2] + " x " + values[3]
                        + ", " + values[0] + ", " + values[1]
                        + ", " + values[7] + ", " + values[5] + ", " + values[8]);
            }
        } catch (Exception e) {
            Log.w(TAG, "readSensorConfig: " + e.getMessage());
        }
    }

    public Bundle getInDisplaySensorArea() {
        return getInDisplaySensorArea(new Bundle());
    }

    public Bundle getInDisplaySensorArea(Bundle b) {
        final String[] area = {
                mSemSensorAreaWidth,
                mSemSensorAreaHeight,
                mSemSensorMarginBottom,
                mSemSensorMarginLeft,
                mSemSensorImageSize,
                mSemSensorActiveArea,
                mSemSensorDraggingArea
        };
        b.putStringArray("sem_area", area);

        if (IS_OPTICAL && mOpticalImpl != null) {
            b.putFloat("brightness", mOpticalImpl.mMaxBrightness);
            b.putFloat("lightColor", mOpticalImpl.mNits);
            b.putString("nits", mOpticalImpl.mBrightnessColor);
        }
        return b;
    }

    public void setFodRect() {
        final Point size = new Point();
        try {
            final DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
            final IWindowManager windowManager = WindowManagerGlobal.getWindowManagerService();
            windowManager.getInitialDisplaySize(0, size);

            if (Utils.isCutoutNotchHidden(mContext)) {
                size.y += SystemProperties.getInt("persist.sys.displayinset.top", 0);
            }

            final int widthPx = metrics.widthPixels;
            final double widthInch = widthPx / metrics.xdpi;
            final double maxResXdpi = size.x / widthInch;

            // mm -> inch = 0.03937007859...
            final double MM_TO_INCH = 0.03937007859349251d;

            final double fingerActiveArea =
                    Double.parseDouble(mSemSensorActiveArea) * maxResXdpi * MM_TO_INCH;
            final double fingerSensorMarginBottom =
                    Double.parseDouble(mSemSensorMarginBottom) * maxResXdpi * MM_TO_INCH;
            final double fingerSensorMarginLeft =
                    Double.parseDouble(mSemSensorMarginLeft) * maxResXdpi * MM_TO_INCH;
            final double fingerSensorHeight =
                    Double.parseDouble(mSemSensorAreaHeight) * maxResXdpi * MM_TO_INCH;

            final int posX = (((int) fingerActiveArea) / 2) - ((int) fingerSensorMarginLeft);
            final int posY = ((int) fingerSensorMarginBottom)
                    + (((int) fingerSensorHeight) / 2)
                    + (((int) fingerActiveArea) / 2);

            final Rect rect = new Rect();
            rect.left = (size.x / 2) - posX;
            rect.right = rect.left + ((int) fingerActiveArea);
            rect.top = size.y - posY;
            rect.bottom = rect.top + ((int) fingerActiveArea);

            setTspMode(String.format(Locale.ENGLISH,
                    "set_fod_rect,%d,%d,%d,%d",
                    rect.left, rect.top, rect.right, rect.bottom));
        } catch (Exception e) {
            Log.w(TAG, "setFodRect: ", e);
        }
    }

    public Rect getFodSensorAreaRectForKeyguard() {
        final Rect rect = getFodSensorAreaRect(-1, null);
        try {
            final WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
            final DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();

            final int margin =
                    (((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM,
                            Float.parseFloat(mSemSensorActiveArea), metrics))
                            - ((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM,
                            Float.parseFloat(mSemSensorImageSize), metrics)))
                            + ((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM,
                            Float.parseFloat(mSemSensorDraggingArea), metrics));

            final int rotation = wm.getDefaultDisplay().getRotation();
            // Samsung expands all sides in every rotation (same effect, different branch ordering).
            rect.left -= margin;
            rect.right += margin;
            rect.top -= margin;
            rect.bottom += margin;

            if (DEBUG) {
                Log.d(TAG, "getFodSensorAreaRectForKeyguard(" + rotation + "): " + rect.toShortString());
            }
        } catch (Exception e) {
            Log.w(TAG, "getFodSensorAreaRectForKeyguard: " + e.getMessage());
        }
        return rect;
    }

    public Rect getFodSensorAreaRect(int argRotation, Point argSize) {
        final Rect rect = new Rect();
        if (!SemBiometricFeature.FP_FEATURE_SENSOR_IS_IN_DISPLAY_TYPE) {
            return rect;
        }

        try {
            final WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);

            final Point size;
            if (argSize == null) {
                size = new Point();
                wm.getDefaultDisplay().getRealSize(size);
            } else {
                size = argSize;
            }

            final int rotation = (argRotation < 0)
                    ? wm.getDefaultDisplay().getRotation()
                    : argRotation;

            final DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();

            final float fingerImageSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_MM, Float.parseFloat(mSemSensorImageSize), metrics);
            final float fingerSensorMarginBottom = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_MM, Float.parseFloat(mSemSensorMarginBottom), metrics);
            final float fingerSensorMarginLeft = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_MM, Float.parseFloat(mSemSensorMarginLeft), metrics);
            final float fingerSensorHeight = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_MM, Float.parseFloat(mSemSensorAreaHeight), metrics);

            final int posX = (((int) fingerImageSize) / 2) - ((int) fingerSensorMarginLeft);
            final int posY = ((int) fingerSensorMarginBottom)
                    + (((int) fingerSensorHeight) / 2)
                    + (((int) fingerImageSize) / 2);

            if (rotation == 0) {
                rect.left = (size.x / 2) - posX;
                rect.right = rect.left + ((int) fingerImageSize);
                rect.top = size.y - posY;
                rect.bottom = rect.top + ((int) fingerImageSize);
            } else if (rotation == 1) {
                rect.left = size.x - posY;
                rect.right = rect.left + ((int) fingerImageSize);
                rect.bottom = (size.y / 2) + posX;
                rect.top = rect.bottom - ((int) fingerImageSize);
            } else if (rotation == 2) {
                rect.right = (size.x / 2) + posX;
                rect.left = rect.right - ((int) fingerImageSize);
                rect.bottom = posY;
                rect.top = rect.bottom - ((int) fingerImageSize);
            } else if (rotation == 3) {
                rect.right = posY;
                rect.left = rect.right - ((int) fingerImageSize);
                rect.top = (size.y / 2) - posX;
                rect.bottom = rect.top + ((int) fingerImageSize);
            }

            if (DEBUG) {
                Log.d(TAG, "getFodSensorAreaRect: " + rotation + ", " + rect.toShortString());
            }
        } catch (Exception e) {
            Log.w(TAG, "getFodSensorAreaRect: " + e.getMessage());
        }

        return rect;
    }

    public int getSensorAreaMarginFromBottomForFod() {
        final Rect rect = getFodSensorAreaRect(-1, null);
        final WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        final Point size = new Point();
        wm.getDefaultDisplay().getRealSize(size);
        return size.y - rect.top;
    }

    public Bundle getSensorIconRandomPos(Bundle b) {
        if (mBurnInHelper == null) {
            mBurnInHelper = new BurnInHelper(mContext);
        }
        return mBurnInHelper.getNextPosition(b);
    }

    public void addMaskView(IBinder token, String pkgName) {
        if (IS_OPTICAL && mOpticalImpl != null) {
            mOpticalImpl.addMaskView(token, pkgName);
        }
    }

    public void removeMaskView(IBinder token, String pkgName) {
        if (IS_OPTICAL && mOpticalImpl != null) {
            mOpticalImpl.removeMaskView(token, pkgName);
        }
    }

    public void onBootCompleted(FingerprintService.Injector injector) {
        if (IS_OPTICAL && mOpticalImpl != null) {
            mOpticalImpl.init(injector);
        }
    }

    public void dump(PrintWriter pw) {
        pw.println(" FOD : " + getInDisplaySensorArea().toString());
        if (IS_OPTICAL) {
            pw.println(" Optical, HW_LS : " + SemBiometricFeature.FP_FEATURE_HW_LIGHT_SOURCE);
            if (mOpticalImpl != null) {
                pw.println(" Optical, B : " + mOpticalImpl.mMaxBrightness);
                pw.println(" Optical, N : " + mOpticalImpl.mNits);
                pw.println(" Optical, C : " + mOpticalImpl.mBrightnessColor);
                for (MaskClient c : mOpticalImpl.mMaskClientList) {
                    pw.println(" Optical, M : " + c.getPackageName());
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // MaskClient
    // ------------------------------------------------------------------------

    private final class MaskClient implements IBinder.DeathRecipient {
        private final boolean mIsCalibrationMode;
        private boolean mIsKeyguard;
        private String mPackageName;
        private int mSessionId;
        private IBinder mToken;

        MaskClient(IBinder token, String pkgName, boolean calibrationMode, int calibrationType)
                throws RemoteException {
            mToken = token;
            mPackageName = pkgName;
            mIsCalibrationMode = calibrationMode;

            final Bundle b = new Bundle();
            b.putString("KEY_PACKAGE_NAME", pkgName);

            if ("com.android.systemui".equals(pkgName)) {
                mIsKeyguard = true;
                b.putBoolean("KEY_KEYGUARD", true);
            }

            try {
                token.linkToDeath(this, 0);
            } catch (Exception e) {
                Log.w(TAG, "MaskClient: linkToDeath: " + e.getMessage());
            }

            int cmd = 500;
            if (mIsCalibrationMode) {
                cmd = 501;
                if (mOpticalImpl != null) {
                    if (calibrationType == OPTICAL_CALIBRATION_NORMAL) {
                        b.putString("nits", mOpticalImpl.mBrightnessColor);
                    } else if (calibrationType == OPTICAL_CALIBRATION_LOW_BRIGHTNESS) {
                        b.putString("nits", mOpticalImpl.mBrightnessColorForLowBrightness);
                    }
                }
            }

            mSessionId = SemBiometricSysUiManager.get().openSession(toString(), token, null);
            SemBiometricSysUiManager.get().sendCommand(mSessionId, cmd, 1, b);
        }

        @Override
        public void binderDied() {
            Log.i(TAG, "MaskClient: binderDied, " + mPackageName);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleClientDeath();
                }
            });
        }

        private void handleClientDeath() {
            if (mIsCalibrationMode) {
                SemInDisplaySensorImpl.getOpticalSensorHelper()
                        .handleCalibrationMode(mToken, mPackageName, 0);
            } else {
                SemInDisplaySensorImpl.this.removeMaskView(mToken, mPackageName);
            }
        }

        void destroy() {
            try {
                mToken.unlinkToDeath(this, 0);
            } catch (Exception e) {
                Log.w(TAG, "MaskClient: destroy: " + e.getMessage());
            }

            final int cmd = mIsCalibrationMode ? 501 : 500;
            SemBiometricSysUiManager.get().sendCommand(mSessionId, cmd, 0, null);
            SemBiometricSysUiManager.get().closeSession(mSessionId);

            mToken = null;
            mPackageName = null;
        }

        IBinder getToken() {
            return mToken;
        }

        String getPackageName() {
            return mPackageName;
        }

        boolean isKeyguard() {
            return mIsKeyguard;
        }
    }

    // ------------------------------------------------------------------------
    // OpticalSensorHelper
    // ------------------------------------------------------------------------

    public final class OpticalSensorHelper {
        private static final String MAX_BRIGHTNESS_PATH = "/sys/class/lcd/panel/mask_brightness";

        private A11yVisibilityLimit mA11yVisibilityLimit;         // Samsung class (external)
        private DisplayAdjustmentManager mDisplayAdjManager;      // Samsung class (external)
        private DisplayManagerInternal mDisplayManagerInternal;

        private Runnable mRunnableDisableFunctionForLightSource;
        private Runnable mRunnableRestoreFunctionForLightSource;

        private float mMaxBrightness = PackageSettingsManager.AspectRatioValue.FULL_SCREEN;
        private int mNits = 0;

        private String mBrightnessColor;
        private String mBrightnessColorForLowBrightness;

        private MaskClient mCalibrationClient;
        private MaskClient mCurClient;

        private final ArrayList<MaskClient> mMaskClientList = new ArrayList<>();

        OpticalSensorHelper() {
            if (!SemBiometricFeature.FP_FEATURE_HW_LIGHT_SOURCE) {
                mRunnableDisableFunctionForLightSource = new Runnable() {
                    @Override
                    public void run() {
                        disableDisplayAdjustFunc();
                        disableA11yVisibilityOpt();
                    }
                };
                mRunnableRestoreFunctionForLightSource = new Runnable() {
                    @Override
                    public void run() {
                        restoreDisplayAdjustFunc();
                        restoreA11yVisibilityOpt();
                    }
                };
            }
        }

        public void disableFunctionForLightSource() {
            if (SemBiometricFeature.FP_FEATURE_HW_LIGHT_SOURCE) return;
            Utils.BioBgThread.getHandler().removeCallbacks(mRunnableRestoreFunctionForLightSource);
            Utils.BioBgThread.getHandler().post(mRunnableDisableFunctionForLightSource);
        }

        public void restoreFunctionForLightSource(long delayMs) {
            if (SemBiometricFeature.FP_FEATURE_HW_LIGHT_SOURCE) return;
            Utils.BioBgThread.getHandler().removeCallbacks(mRunnableDisableFunctionForLightSource);
            Utils.BioBgThread.getHandler().postDelayed(mRunnableRestoreFunctionForLightSource, delayMs);
        }

        private void disableDisplayAdjustFunc() {
            if (mDisplayAdjManager == null) {
                mDisplayAdjManager = new DisplayAdjustmentManager(mContext);
            }
            mDisplayAdjManager.disable();
        }

        private void restoreDisplayAdjustFunc() {
            if (mDisplayAdjManager != null) {
                mDisplayAdjManager.restore();
                mDisplayAdjManager = null;
            }
        }

        private void disableA11yVisibilityOpt() {
            if (mA11yVisibilityLimit == null) {
                mA11yVisibilityLimit = new A11yVisibilityLimit(mContext);
            }
            mA11yVisibilityLimit.disable();
        }

        private void restoreA11yVisibilityOpt() {
            if (mA11yVisibilityLimit != null) {
                mA11yVisibilityLimit.restore();
                mA11yVisibilityLimit = null;
            }
        }

        private boolean hasMaskClient(IBinder token) {
            for (MaskClient c : mMaskClientList) {
                if (c.getToken() == token) return true;
            }
            return false;
        }

        private MaskClient getMaskClient(IBinder token) {
            for (MaskClient c : mMaskClientList) {
                if (c.getToken() == token) return c;
            }
            return null;
        }

        void setDisplayStateLimit(boolean on) {
            try {
                if (mDisplayManagerInternal == null) {
                    mDisplayManagerInternal =
                            (DisplayManagerInternal) LocalServices.getService(DisplayManagerInternal.class);
                }
                if (mDisplayManagerInternal == null) return;

                mDisplayManagerInternal.setDisplayStateLimit(on ? 2 : 0);
            } catch (Exception e) {
                Log.e(TAG, "setDisplayStateLimit: ", e);
            }
        }

        public boolean isActivatedMaskByClient() {
            synchronized (mMaskClientList) {
                return mCurClient != null;
            }
        }

        public void addMaskView(IBinder token, String pkg) {
            synchronized (mMaskClientList) {
                if (hasMaskClient(token)) {
                    Log.i(TAG, "addMaskView: already registered client: [" + token + "], [" + pkg + "]");
                    return;
                }
                try {
                    MaskClient newClient = new MaskClient(token, pkg, false, 0);
                    mMaskClientList.add(newClient);
                    mCurClient = newClient;
                } catch (RemoteException e) {
                    Log.w(TAG, "addMaskView: ", e);
                }
            }
        }

        public void removeMaskView(IBinder token, String pkg) {
            synchronized (mMaskClientList) {
                MaskClient c = getMaskClient(token);
                if (c == null) {
                    Log.i(TAG, "removeMaskView: No registered client: " + token);
                    return;
                }

                mMaskClientList.remove(c);
                c.destroy();

                if (mMaskClientList.isEmpty()) {
                    mCurClient = null;
                } else if (mCurClient != null && mCurClient.getToken() == token) {
                    mCurClient = mMaskClientList.get(0);
                    Log.d(TAG, "removeMaskView: new current client: " + mCurClient.getPackageName());
                }
            }
        }

        public void handleCalibrationMode(IBinder token, String pkg, int param) {
            if (param >= 1) {
                if (mCalibrationClient != null) {
                    mCalibrationClient.destroy();
                    mCalibrationClient = null;
                }
                try {
                    mCalibrationClient = new MaskClient(token, pkg, true, param);
                } catch (RemoteException e) {
                    Log.w(TAG, "handleCalibrationMode: ", e);
                }
                return;
            }

            if (mCalibrationClient == null) {
                Log.d(TAG, "handleCalibrationMode: No Calibration Client");
                return;
            }
            mCalibrationClient.destroy();
            mCalibrationClient = null;
        }

        public boolean hasKeyguardMaskClient() {
            synchronized (mMaskClientList) {
                for (MaskClient c : mMaskClientList) {
                    if (c.isKeyguard()) return true;
                }
                return false;
            }
        }

        private void init(FingerprintService.Injector injector) {
            mNits = getBrightnessNitsValue(injector);
            writeMaxBrightnessInfo();
        }

        private void writeMaxBrightnessInfo() {
            getBrightnessCorrespondingToNits();
            Utils.BioBgThread.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Utils.writeFile(new File(MAX_BRIGHTNESS_PATH),
                                Integer.toString((int) mMaxBrightness).getBytes());
                    } catch (IOException e) {
                        Log.w(TAG, "writeMaxBrightnessInfo: ", e);
                    }
                }
            });
        }

        private float getBrightnessCorrespondingToNits() {
            if (mMaxBrightness <= PackageSettingsManager.AspectRatioValue.FULL_SCREEN) {
                SemDisplaySolutionManager dsm =
                        (SemDisplaySolutionManager) mContext.getSystemService(SemDisplaySolutionManager.class);
                if (dsm != null) {
                    mMaxBrightness = dsm.getFingerPrintBacklightValue(mNits);
                }

                if (mMaxBrightness <= PackageSettingsManager.AspectRatioValue.FULL_SCREEN) {
                    Log.w(TAG, "getBrightnessCorrespondingToNits: use default value, " + mMaxBrightness);
                    mMaxBrightness = 319.0f;
                }
            }

            if (DEBUG || Utils.isDebugLevelMid()) {
                Log.d(TAG, "getBrightnessCorrespondingToNits: " + mMaxBrightness);
            }
            return mMaxBrightness;
        }

        private int getBrightnessNitsValue(FingerprintService.Injector injector) {
            int retNits = 525;
            try {
                byte[] tempOutput = new byte[256];
                int result = injector.request(32, 0, null, tempOutput);

                if (result > 0) {
                    byte[] raw = Arrays.copyOf(tempOutput, result);
                    String[] values = new String(raw).trim().split(",");

                    if (values.length > 0) {
                        Log.d(TAG, "getBrightnessNitsValue: node = " + values[0]);
                        retNits = Integer.parseInt(values[0]);
                    }
                    if (values.length > 3) {
                        Log.d(TAG, "getBrightnessNitsColor: node = " + values[3]);
                        mBrightnessColor = values[3];
                    }
                    if (values.length > 4) {
                        Log.d(TAG, "getBrightnessNitsColor(low): node = " + values[4]);
                        mBrightnessColorForLowBrightness = values[4];
                    }
                } else {
                    Log.w(TAG, "getBrightnessNitsValue: failed to read from HAL, " + result);

                    // NOTE: Samsung used android.R.array.config_displayWhiteBalanceIncreaseThresholds here.
                    // This is likely just a decompile artifact or Samsung overlay. Keep it as-is.
                    String[] arr = mContext.getResources().getStringArray(
                            R.array.config_displayWhiteBalanceIncreaseThresholds);
                    int maxNits = Integer.parseInt(arr[arr.length - 1]);
                    if (maxNits < 525) {
                        retNits = maxNits;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "getBrightnessNitsValue: failure to read nits info: " + e.getMessage());
            }

            if (DEBUG) {
                Log.i(TAG, "getBrightnessNitsValue: nits = [" + retNits + "]");
            }
            return retNits;
        }
    }
}
