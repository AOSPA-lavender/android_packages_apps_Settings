/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.fuelgauge.datasaver;

import static android.net.NetworkPolicyManager.POLICY_NONE;
import static android.net.NetworkPolicyManager.POLICY_REJECT_METERED_BACKGROUND;

import static com.android.settings.Utils.SETTINGS_PACKAGE_NAME;
import static com.android.settings.fuelgauge.BatteryUtils.UID_ZERO;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.NetworkPolicyManager;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import java.util.List;
import java.util.Set;

/** A class to dynamically manage per apps {@link NetworkPolicyManager} POLICY_ flags. */
public final class DynamicDenylistManager {

    private static final String TAG = "DynamicDenylistManager";
    private static final String PREF_KEY_MANUAL_DENY = "manual_denylist_preference";
    private static final String PREF_KEY_DYNAMIC_DENY = "dynamic_denylist_preference";

    private static DynamicDenylistManager sInstance;

    private final Context mContext;
    private final NetworkPolicyManager mNetworkPolicyManager;
    private final Object mLock = new Object();

    @VisibleForTesting
    static final String PREF_KEY_MANUAL_DENYLIST_SYNCED = "manual_denylist_synced";

    /** @return a DynamicDenylistManager object */
    public static DynamicDenylistManager getInstance(Context context) {
        synchronized (DynamicDenylistManager.class) {
            if (sInstance == null) {
                sInstance = new DynamicDenylistManager(
                        context, NetworkPolicyManager.from(context));
            }
            return sInstance;
        }
    }

    @VisibleForTesting
    DynamicDenylistManager(Context context, NetworkPolicyManager networkPolicyManager) {
        mContext = context.getApplicationContext();
        mNetworkPolicyManager = networkPolicyManager;
        syncPolicyIfNeeded();
    }

    /** Sync the policy from {@link NetworkPolicyManager} if needed. */
    private void syncPolicyIfNeeded() {
        if (getManualDenylistPref().contains(PREF_KEY_MANUAL_DENYLIST_SYNCED)) {
            Log.i(TAG, "syncPolicyIfNeeded() ignore synced manual denylist");
            return;
        }

        final SharedPreferences.Editor editor = getManualDenylistPref().edit();
        final int[] existedUids = mNetworkPolicyManager
                .getUidsWithPolicy(POLICY_REJECT_METERED_BACKGROUND);
        if (existedUids != null && existedUids.length != 0) {
            for (int uid : existedUids) {
                editor.putInt(String.valueOf(uid), POLICY_REJECT_METERED_BACKGROUND);
            }
        }
        editor.putInt(PREF_KEY_MANUAL_DENYLIST_SYNCED, POLICY_NONE).apply();
    }

    /** Set policy flags for specific UID. */
    public void setUidPolicyLocked(int uid, int policy) {
        synchronized (mLock) {
            mNetworkPolicyManager.setUidPolicy(uid, policy);
        }
        updateDenylistPref(uid, policy);
    }

    /** Suggest a list of package to set as POLICY_REJECT. */
    public void setDenylist(List<String> packageNameList) {
        final Set<Integer> denylistTargetUids = new ArraySet<>(packageNameList.size());
        for (String packageName : packageNameList) {
            try {
                final int uid = mContext.getPackageManager().getPackageUid(packageName, 0);
                if (uid == UID_ZERO) {
                    continue;
                }
                denylistTargetUids.add(uid);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Unknown package name: " + packageName, e);
            }
        }

        final Set<Integer> manualDenylistUids = getDenylistAllUids(getManualDenylistPref());
        denylistTargetUids.removeAll(manualDenylistUids);

        final Set<Integer> lastDynamicDenylistUids = getDenylistAllUids(getDynamicDenylistPref());
        if (lastDynamicDenylistUids.equals(denylistTargetUids)) {
            Log.i(TAG, "setDenylist() ignore the same denylist with size: "
                    + lastDynamicDenylistUids.size());
            return;
        }

        // Store target denied uids into DynamicDenylistPref.
        final SharedPreferences.Editor editor = getDynamicDenylistPref().edit();
        editor.clear();
        denylistTargetUids.forEach(
                uid -> editor.putInt(String.valueOf(uid), POLICY_REJECT_METERED_BACKGROUND));
        editor.apply();

        // Set new added UIDs into REJECT policy.
        synchronized (mLock) {
            for (int uid : denylistTargetUids) {
                if (!lastDynamicDenylistUids.contains(uid)) {
                    mNetworkPolicyManager.setUidPolicy(uid, POLICY_REJECT_METERED_BACKGROUND);
                }
            }
        }
        // Unset removed UIDs back to NONE policy.
        synchronized (mLock) {
            for (int uid : lastDynamicDenylistUids) {
                if (!denylistTargetUids.contains(uid)) {
                    mNetworkPolicyManager.setUidPolicy(uid, POLICY_NONE);
                }
            }
        }
    }

    /** Return true if the target uid is in {@link #getManualDenylistPref()}. */
    public boolean isInManualDenylist(int uid) {
        return getManualDenylistPref().contains(String.valueOf(uid));
    }

    /** Reset the UIDs in the denylist if needed. */
    public void resetDenylistIfNeeded(String packageName, boolean force) {
        if (!force && !SETTINGS_PACKAGE_NAME.equals(packageName)) {
            return;
        }
        synchronized (mLock) {
            for (int uid : mNetworkPolicyManager
                    .getUidsWithPolicy(POLICY_REJECT_METERED_BACKGROUND)) {
                if (!getDenylistAllUids(getManualDenylistPref()).contains(uid)) {
                    mNetworkPolicyManager.setUidPolicy(uid, POLICY_NONE);
                }
            }
        }
        clearSharedPreferences();
    }

    private Set<Integer> getDenylistAllUids(SharedPreferences sharedPreferences) {
        final ArraySet<Integer> uids = new ArraySet<>();
        for (String key : sharedPreferences.getAll().keySet()) {
            if (PREF_KEY_MANUAL_DENYLIST_SYNCED.equals(key)) {
                continue;
            }
            try {
                uids.add(Integer.parseInt(key));
            } catch (NumberFormatException e) {
                Log.e(TAG, "getDenylistAllUids() unexpected format for " + key);
            }
        }
        return uids;
    }

    void updateDenylistPref(int uid, int policy) {
        final String uidString = String.valueOf(uid);
        if (policy != POLICY_REJECT_METERED_BACKGROUND) {
            getManualDenylistPref().edit().remove(uidString).apply();
        } else {
            getManualDenylistPref().edit().putInt(uidString, policy).apply();
        }
        getDynamicDenylistPref().edit().remove(uidString).apply();
    }

    void clearSharedPreferences() {
        getManualDenylistPref().edit().clear().apply();
        getDynamicDenylistPref().edit().clear().apply();
    }

    @VisibleForTesting
    SharedPreferences getManualDenylistPref() {
        return mContext.getSharedPreferences(PREF_KEY_MANUAL_DENY, Context.MODE_PRIVATE);
    }

    @VisibleForTesting
    SharedPreferences getDynamicDenylistPref() {
        return mContext.getSharedPreferences(PREF_KEY_DYNAMIC_DENY, Context.MODE_PRIVATE);
    }
}
