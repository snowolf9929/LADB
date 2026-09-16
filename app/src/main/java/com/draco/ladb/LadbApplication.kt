package com.draco.ladb

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.core.content.edit
import androidx.preference.PreferenceManager

class LadbApplication : Application(), Application.ActivityLifecycleCallbacks {
    private companion object {
        /**
         * Bump this when `default_bookmarks` changes, so an existing install
         * picks the change up once. Commands the user deleted under an older
         * version come back with it.
         */
        const val DEFAULT_BOOKMARKS_VERSION = 3
    }

    override fun onCreate() {
        super.onCreate()

        seedDefaultBookmarks()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerActivityLifecycleCallbacks(this)
        }
    }

    /**
     * Put the bundled commands into the bookmarks of a fresh install, and bring
     * an existing install in step with a newer version: the new commands are
     * added, the ones that were dropped are taken out, and bookmarks the user
     * added are left alone.
     */
    private fun seedDefaultBookmarks() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val versionKey = getString(R.string.bookmarks_version_key)

        if (prefs.getInt(versionKey, 0) >= DEFAULT_BOOKMARKS_VERSION)
            return

        val bookmarksKey = getString(R.string.bookmarks_key)
        val existing = prefs.getStringSet(bookmarksKey, emptySet()) ?: emptySet()
        val defaults = resources.getStringArray(R.array.default_bookmarks)
        val retired = resources.getStringArray(R.array.retired_bookmarks).toSet()

        prefs.edit {
            putStringSet(bookmarksKey, (existing - retired) + defaults)
            putInt(versionKey, DEFAULT_BOOKMARKS_VERSION)
        }
    }

    /**
     * Apply the system palette directly, as Material only does so for a list of vendors
     */
    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        activity.theme.applyStyle(R.style.ThemeOverlay_LADB_DynamicColors, true)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
