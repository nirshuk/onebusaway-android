/*
 * Copyright (C) 2011-2015 Paul Watts (paulcwatts@gmail.com), University of South Florida
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
package com.joulespersecond.seattlebusbot;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.preference.PreferenceManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.google.android.gms.analytics.GoogleAnalytics;
import com.google.android.gms.analytics.Tracker;
import com.joulespersecond.oba.ObaAnalytics;
import com.joulespersecond.oba.ObaApi;
import com.joulespersecond.oba.elements.ObaRegion;
import com.joulespersecond.oba.provider.ObaContract.Regions;
import com.joulespersecond.seattlebusbot.util.PreferenceHelp;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.UUID;

public class Application extends android.app.Application {

    public static final String APP_UID = "app_uid";

    // Region preference (long id)
    private static final String TAG = "Application";

    //private static final String PREFS_NAME = "com.joulespersecond.seattlebusbot.prefs";
    private SharedPreferences mPrefs;

    private static Application mApp;

    /**
     * Google analytics tracker configs
     */
    public enum TrackerName {
        APP_TRACKER, // Tracker used only in this app.
        GLOBAL_TRACKER, // Tracker used by all the apps from a company. eg: roll-up tracking.
    }

    HashMap<TrackerName, Tracker> mTrackers = new HashMap<TrackerName, Tracker>();

    @Override
    public void onCreate() {
        super.onCreate();
        //ExceptionHandler.register(this, BUG_REPORT_URL);

        mApp = this;
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Fix bugs in pre-Froyo
        disableConnectionReuseIfNecessary();

        initOba();
        initObaRegion();

        ObaAnalytics.initAnalytics(this);
        reportAnalytics();
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        mApp = null;
    }

    //
    // Public helpers
    //
    public static Application get() {
        return mApp;
    }

    public static SharedPreferences getPrefs() {
        return get().mPrefs;
    }

    //
    // Helper to get/set the regions
    //
    public synchronized ObaRegion getCurrentRegion() {
        return ObaApi.getDefaultContext().getRegion();
    }

    public synchronized void setCurrentRegion(ObaRegion region) {
        if (region != null) {
            // First set it in preferences, then set it in OBA.
            ObaApi.getDefaultContext().setRegion(region);
            PreferenceHelp
                    .saveLong(mPrefs, getString(R.string.preference_key_region), region.getId());
            //We're using a region, so clear the custom API URL preference
            setCustomApiUrl(null);
        } else {
            //User must have just entered a custom API URL via Preferences, so clear the region info
            ObaApi.getDefaultContext().setRegion(null);
            PreferenceHelp.saveLong(mPrefs, getString(R.string.preference_key_region), -1);
        }
    }

    /**
     * Gets the date at which the region information was last updated, in the number of
     * milliseconds
     * since January 1, 1970, 00:00:00 GMT
     * Default value is 0 if the region info has never been updated.
     *
     * @return the date at which the region information was last updated, in the number of
     * milliseconds since January 1, 1970, 00:00:00 GMT.  Default value is 0 if the region info has
     * never been updated.
     */
    public long getLastRegionUpdateDate() {
        SharedPreferences preferences = getPrefs();
        return preferences.getLong(getString(R.string.preference_key_last_region_update), 0);
    }

    /**
     * Sets the date at which the region information was last updated
     *
     * @param date the date at which the region information was last updated, in the number of
     *             milliseconds since January 1, 1970, 00:00:00 GMT
     */
    public void setLastRegionUpdateDate(long date) {
        PreferenceHelp
                .saveLong(mPrefs, getString(R.string.preference_key_last_region_update), date);
    }

    /**
     * Returns the custom URL if the user has set a custom API URL manually via Preferences, or
     * null
     * if it has not been set
     *
     * @return the custom URL if the user has set a custom API URL manually via Preferences, or null
     * if it has not been set
     */
    public String getCustomApiUrl() {
        SharedPreferences preferences = getPrefs();
        return preferences.getString(getString(R.string.preference_key_oba_api_url), null);
    }

    /**
     * Sets the custom URL used to reach a OBA REST API server that is not available via the
     * Regions
     * REST API
     *
     * @param url the custom URL
     */
    public void setCustomApiUrl(String url) {
        PreferenceHelp.saveString(getString(R.string.preference_key_oba_api_url), url);
    }

    private static final String HEXES = "0123456789abcdef";

    public static String getHex(byte[] raw) {
        final StringBuilder hex = new StringBuilder(2 * raw.length);
        for (byte b : raw) {
            hex.append(HEXES.charAt((b & 0xF0) >> 4))
                    .append(HEXES.charAt((b & 0x0F)));
        }
        return hex.toString();
    }

    private String getAppUid() {
        try {
            final TelephonyManager telephony =
                    (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            final String id = telephony.getDeviceId();
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(id.getBytes());
            return getHex(digest.digest());
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private void initOba() {
        String uuid = mPrefs.getString(APP_UID, null);
        if (uuid == null) {
            // Generate one and save that.
            uuid = getAppUid();
            PreferenceHelp.saveString(APP_UID, uuid);
        }

        // Get the current app version.
        PackageManager pm = getPackageManager();
        PackageInfo appInfo = null;
        try {
            appInfo = pm.getPackageInfo(getPackageName(), PackageManager.GET_META_DATA);
        } catch (NameNotFoundException e) {
            // Do nothing, perhaps we'll get to show it again? Or never.
            return;
        }
        ObaApi.getDefaultContext().setAppInfo(appInfo.versionCode, uuid);
    }

    private void initObaRegion() {
        // Read the region preference, look it up in the DB, then set the region.
        long id = mPrefs.getLong(getString(R.string.preference_key_region), -1);
        if (id < 0) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Regions preference ID is less than 0, returning...");
            }
            return;
        }

        ObaRegion region = Regions.get(this, (int) id);
        if (region == null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Regions preference is null, returning...");
            }
            return;
        }

        ObaApi.getDefaultContext().setRegion(region);
    }

    private void disableConnectionReuseIfNecessary() {
        // Work around pre-Froyo bugs in HTTP connection reuse.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.FROYO) {
            System.setProperty("http.keepAlive", "false");
        }
    }

    public synchronized Tracker getTracker(TrackerName trackerId) {
        if (!mTrackers.containsKey(trackerId)) {

            GoogleAnalytics analytics = GoogleAnalytics.getInstance(this);
            Tracker t = (trackerId == TrackerName.APP_TRACKER) ? analytics.newTracker(R.xml.app_tracker)
                    : (trackerId == TrackerName.GLOBAL_TRACKER) ? analytics.newTracker(R.xml.global_tracker)
                    : analytics.newTracker(R.xml.global_tracker);
            mTrackers.put(trackerId, t);
        }
        return mTrackers.get(trackerId);
    }

    private void reportAnalytics() {
        if (getCustomApiUrl() == null && getCurrentRegion() != null) {
            ObaAnalytics.reportEventWithCategory(ObaAnalytics.ObaEventCategory.APP_SETTINGS.toString(),
                    getString(R.string.analytics_action_configured_region), getString(R.string.analytics_label_region)
                            + getCurrentRegion().getName());
        } else {
            String customUrl = null;
            MessageDigest digest = null;
            try {
                digest = MessageDigest.getInstance("SHA-1");
                digest.update(getCustomApiUrl().getBytes());
                customUrl = getString(R.string.analytics_label_custom_url) +
                        ": " + getHex(digest.digest());
            } catch (Exception e) {
                customUrl = Application.get().getString(R.string.analytics_label_custom_url);
            }
            ObaAnalytics.reportEventWithCategory(ObaAnalytics.ObaEventCategory.APP_SETTINGS.toString(),
                    getString(R.string.analytics_action_configured_region), getString(R.string.analytics_label_region)
                            + customUrl);
        }

        if (getCurrentRegion() != null) {
            Boolean showExperimentalRegions = getCurrentRegion().getExperimental();
            ObaAnalytics.reportEventWithCategory(ObaAnalytics.ObaEventCategory.APP_SETTINGS.toString(),
                    getString(R.string.analytics_action_configured_region), getString(R.string.analytics_label_region)
                            + (showExperimentalRegions ? "YES" : "NO"));
        }

        Boolean experimentalRegions = getPrefs().getBoolean(getString(R.string.preference_key_experimental_regions),
                Boolean.FALSE);
        Boolean autoRegion = getPrefs().getBoolean(getString(R.string.preference_key_auto_select_region),
                Boolean.FALSE);
        ObaAnalytics.reportEventWithCategory(ObaAnalytics.ObaEventCategory.APP_SETTINGS.toString(),
                getString(R.string.analytics_action_edit_general), getString(R.string.analytics_label_experimental)
                        + (experimentalRegions ? "YES" : "NO"));
        ObaAnalytics.reportEventWithCategory(ObaAnalytics.ObaEventCategory.APP_SETTINGS.toString(),
                getString(R.string.analytics_action_edit_general), getString(R.string.analytics_label_region_auto)
                        + (autoRegion ? "YES" : "NO"));
    }
}
