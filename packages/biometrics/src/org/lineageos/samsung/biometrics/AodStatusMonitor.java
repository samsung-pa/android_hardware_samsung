package org.lineageos.samsung.biometrics.fingerprint;

import static android.provider.Settings.Secure.DOZE_ALWAYS_ON;
import static android.provider.Settings.Secure.DOZE_ENABLED;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.AmbientDisplayConfiguration;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Log;

import java.util.Calendar;
import java.util.Set;

/**
 * AOSP/Lineage-friendly AOD/Doze status monitor.
 *
 * Notes:
 * - AOSP doesn't have Samsung's "aod_show_state". We approximate "showing" as:
 *   Always-on enabled + Doze enabled + (optionally) within schedule.
 * - If you want real "currently dozing" state, you need to integrate with SystemUI
 *   (DozeHost / StatusBarStateController) which is not a stable public API.
 */
public final class AodStatusMonitor {
    private static final String TAG = "SB_AodStatusMonitor";

    // Optional custom schedule keys (you define and manage these).
    // Minutes since midnight (0..1439). If start == end => treat as "no schedule restriction".
    private static final String KEY_AOD_SCHEDULE_START = "aod_schedule_start_min";
    private static final String KEY_AOD_SCHEDULE_END   = "aod_schedule_end_min";
    private static final String KEY_AOD_SCHEDULE_ENABLED = "aod_schedule_enabled";

    public interface Callback {
        void onAodStart();
        void onAodStop();
    }

    private final Context mContext;
    private final Set<Callback> mCallbacks = new ArraySet<>();

    // Values
    private boolean mIsDozeEnabled;
    private boolean mIsAlwaysOnEnabled;
    private boolean mIsScheduleEnabled;
    private int mScheduleStartMin;
    private int mScheduleEndMin;

    // Derived "state" to fire callbacks only on changes
    private boolean mLastActive;

    private final ContentObserver mObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            final boolean oldActive = mLastActive;
            updateValue();
            final boolean newActive = isAodActive();
            if (oldActive != newActive) {
                mLastActive = newActive;
                dispatch(newActive);
            }
        }
    };

    public AodStatusMonitor(Context context) {
        mContext = context.getApplicationContext();
        updateValue();
        mLastActive = isAodActive();
    }

    public void register() {
        final ContentResolver cr = mContext.getContentResolver();

        // Observe AOSP Doze toggles
        cr.registerContentObserver(Settings.Secure.getUriFor(DOZE_ENABLED),
                false, mObserver, UserHandle.USER_ALL);
        cr.registerContentObserver(Settings.Secure.getUriFor(DOZE_ALWAYS_ON),
                false, mObserver, UserHandle.USER_ALL);

        // Observe optional schedule keys (if you use them)
        cr.registerContentObserver(Settings.Secure.getUriFor(KEY_AOD_SCHEDULE_ENABLED),
                false, mObserver, UserHandle.USER_ALL);
        cr.registerContentObserver(Settings.Secure.getUriFor(KEY_AOD_SCHEDULE_START),
                false, mObserver, UserHandle.USER_ALL);
        cr.registerContentObserver(Settings.Secure.getUriFor(KEY_AOD_SCHEDULE_END),
                false, mObserver, UserHandle.USER_ALL);
    }

    public void unregister() {
        mContext.getContentResolver().unregisterContentObserver(mObserver);
    }

    public void addCallback(Callback cb) {
        if (cb != null) mCallbacks.add(cb);
    }

    public void removeCallback(Callback cb) {
        if (cb != null) mCallbacks.remove(cb);
    }

    /**
     * "Active" on AOSP = doze enabled AND always-on enabled AND (if schedule enabled) within schedule.
     * This is the closest equivalent to “AOD should be on right now”.
     */
    public boolean isAodActive() {
        if (!mIsDozeEnabled) return false;
        if (!mIsAlwaysOnEnabled) return false;
        if (mIsScheduleEnabled && !isInScheduleTime()) return false;
        return true;
    }

    /**
     * AOSP does not expose Samsung's aod_show_state.
     * If you still want an "isShowing()" method, return the same as isAodActive(),
     * or rename it to avoid confusion.
     */
    public boolean isShowingApprox() {
        return isAodActive();
    }

    public boolean isInScheduleTime() {
        if (!mIsScheduleEnabled) return true;

        final int start = mScheduleStartMin;
        final int end = mScheduleEndMin;

        // If same => treat as no restriction (mirrors many OEMs)
        if (start == end) return true;

        int now = getNowMinutes();

        // Correct cross-midnight handling:
        // If schedule crosses midnight, treat it as [start..1440) U [0..end]
        if (start < end) {
            return now >= start && now <= end;
        } else {
            return now >= start || now <= end;
        }
    }

    public void updateValue() {
        final ContentResolver cr = mContext.getContentResolver();

        // Availability + defaults
        final AmbientDisplayConfiguration adc = new AmbientDisplayConfiguration(mContext);
        final boolean alwaysOnAvailable = adc.alwaysOnAvailable();
        final boolean enabledByDefault = mContext.getResources()
                .getBoolean(com.android.internal.R.bool.config_dozeAlwaysOnEnabled);

        mIsDozeEnabled = Settings.Secure.getIntForUser(cr,
                DOZE_ENABLED, 1, UserHandle.USER_CURRENT) != 0;

        mIsAlwaysOnEnabled = Settings.Secure.getIntForUser(cr,
                DOZE_ALWAYS_ON, (alwaysOnAvailable && enabledByDefault) ? 1 : 0,
                UserHandle.USER_CURRENT) != 0;

        // Optional schedule settings (your ROM/app decides how to set these)
        mIsScheduleEnabled = Settings.Secure.getIntForUser(cr,
                KEY_AOD_SCHEDULE_ENABLED, 0, UserHandle.USER_CURRENT) != 0;

        mScheduleStartMin = Settings.Secure.getIntForUser(cr,
                KEY_AOD_SCHEDULE_START, 0, UserHandle.USER_CURRENT);

        mScheduleEndMin = Settings.Secure.getIntForUser(cr,
                KEY_AOD_SCHEDULE_END, 0, UserHandle.USER_CURRENT);

        Log.i(TAG, "updateValue: doze=" + mIsDozeEnabled
                + " alwaysOn=" + mIsAlwaysOnEnabled
                + " scheduleEnabled=" + mIsScheduleEnabled
                + " start=" + mScheduleStartMin
                + " end=" + mScheduleEndMin
                + " active=" + isAodActive());
    }

    private void dispatch(boolean active) {
        for (Callback cb : mCallbacks) {
            try {
                if (active) cb.onAodStart();
                else cb.onAodStop();
            } catch (Throwable t) {
                Log.w(TAG, "Callback failed", t);
            }
        }
    }

    private static int getNowMinutes() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(System.currentTimeMillis());
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }
}
