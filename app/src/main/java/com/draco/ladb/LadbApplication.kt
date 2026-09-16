package com.draco.ladb

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.core.content.edit
import androidx.preference.PreferenceManager

class LadbApplication : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()

        seedDefaultBookmarks()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerActivityLifecycleCallbacks(this)
        }
    }

    /**
     * Put the bundled commands into the bookmarks the first time the app runs.
     *
     * The latch means a command the user deleted stays deleted, and bookmarks
     * an existing install already had are kept.
     */
    private fun seedDefaultBookmarks() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val seededKey = getString(R.string.bookmarks_seeded_key)

        if (prefs.getBoolean(seededKey, false))
            return

        val bookmarksKey = getString(R.string.bookmarks_key)
        val existing = prefs.getStringSet(bookmarksKey, emptySet()) ?: emptySet()
        val defaults = resources.getStringArray(R.array.default_bookmarks)

        prefs.edit {
            putStringSet(bookmarksKey, existing + defaults)
            putBoolean(seededKey, true)
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
