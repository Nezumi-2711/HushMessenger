package app.hushmessenger.extension;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

/**
 * The way back to the settings page a restart was asked from. Settings hands RestartActivity the page, and it goes
 * into the settings store with the time, as a one-shot marker, in the same commit that saves the choices before the
 * relaunch. The first screen the next process shows takes the marker: it's cleared first, so a page that fails to
 * open isn't tried again on every start, and only a marker younger than MAX_AGE_MS opens settings. One left by a
 * restart that never finished, or a phone switched off, is dropped, and the restart screen clears its own when it gives
 * up without relaunching. The time is the phone's time since it booted, which a clock change can't move. A restart from
 * the home screen shortcut carries no page and clears any older marker, so it lands in Messenger.
 */
final class SettingsReopen {
    /** The extra carrying the page, on the restart screen's intent and on the settings intent it reopens. */
    static final String EXTRA = "app.hushmessenger.page";
    static final String PAGE_KEY = "reopen_settings_page";
    static final String TIME_KEY = "reopen_settings_at";
    /** A slow phone's cold start fits easily, and a later start doesn't count. */
    static final long MAX_AGE_MS = 90_000;

    private SettingsReopen() { }

    /** One of the settings screen's two pages. Anything else is the first one. */
    static String page(String requested) {
        return "app".equals(requested) ? "app" : "controls";
    }

    /**
     * Adds the marker for [page] at [now] to [edit]. A restart that didn't come from settings removes any marker instead,
     * so an older one can't send it there.
     */
    static SharedPreferences.Editor remember(SharedPreferences.Editor edit, String page, long now) {
        return page == null ? edit.remove(PAGE_KEY).remove(TIME_KEY) : edit.putString(PAGE_KEY, page(page)).putLong(TIME_KEY, now);
    }

    /** Drops the marker, for a restart that stopped before Messenger relaunched. */
    static void forget(SharedPreferences prefs) {
        if (prefs != null && (prefs.contains(PAGE_KEY) || prefs.contains(TIME_KEY))) prefs.edit().remove(PAGE_KEY).remove(TIME_KEY).apply();
    }

    /**
     * Takes the marker: clears it, then answers its page if it was written less than MAX_AGE_MS before [now], and
     * null otherwise. A second call answers null. With no marker, nothing is written.
     */
    static String takeFresh(SharedPreferences prefs, long now) {
        if (!prefs.contains(PAGE_KEY) && !prefs.contains(TIME_KEY)) return null;
        String page;
        long at;
        try {
            page = prefs.getString(PAGE_KEY, null);
            at = prefs.getLong(TIME_KEY, -1);
        } catch (ClassCastException wrongType) {
            page = null;
            at = -1;
        }
        // Cleared before anything opens, so a screen that fails to open isn't retried on every start.
        prefs.edit().remove(PAGE_KEY).remove(TIME_KEY).commit();
        long age = now - at;
        return page == null || at < 0 || age < 0 || age >= MAX_AGE_MS ? null : page(page);
    }

    /** True when this install has the app drawer entry and it isn't hidden, so a tap on it can reach settings. */
    static boolean drawerEntryOn(Context context) {
        ComponentName alias = new ComponentName(context.getPackageName(), SettingsActivity.DRAWER_ALIAS);
        PackageManager packages = context.getPackageManager();
        try {
            return packages.getComponentEnabledSetting(alias) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                && packages.resolveActivity(new Intent().setComponent(alias), 0) != null;
        } catch (IllegalArgumentException | SecurityException missing) {
            return false;
        }
    }

    /** Makes [settings] the intent a tap on the app drawer entry sends. Its extras and flags stay as they are. */
    static Intent asDrawerLaunch(Intent settings) {
        return settings.setClassName(settings.getComponent().getPackageName(), SettingsActivity.DRAWER_ALIAS)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    }

    /** HostScreens hands over Messenger's Application here. The first screen this process shows checks the marker once. */
    static void watch(Application app) {
        app.registerActivityLifecycleCallbacks(new FirstScreen(app));
    }

    static final class FirstScreen implements Application.ActivityLifecycleCallbacks {
        private final Application app;

        FirstScreen(Application app) { this.app = app; }

        @Override public void onActivityResumed(Activity activity) {
            app.unregisterActivityLifecycleCallbacks(this);
            try {
                // Settings live in the main process, the one Messenger's own screens open in.
                if (!activity.getPackageName().equals(Application.getProcessName())) return;
                // Settings' own store, opened directly: reading a marker shouldn't start settings and CrashGuard early.
                SharedPreferences prefs = Settings.preferences != null ? Settings.preferences
                    : activity.getApplicationContext().getSharedPreferences("hushmessenger", Context.MODE_PRIVATE);
                String page = takeFresh(prefs, SystemClock.elapsedRealtime());
                // A settings screen that is already up is where the restart meant to go.
                if (page == null || activity instanceof SettingsActivity || activity instanceof RestartActivity) return;
                Intent settings = HostScreens.intentFor(activity, HostScreens.SETTINGS).putExtra(EXTRA, page);
                // Android keeps the settings task across the restart, with the screen the restart was asked from still
                // in it. Opened from the app drawer, that screen's intent names the launcher alias, so this one would be
                // stacked on top and Back would show the older copy. Emptying the task first leaves one settings screen.
                // A Root Mount install's settings are a document task inside the stock host, which is left as it is.
                if (!HostScreens.hosted(activity)) {
                    settings.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    // The task's first intent decides what a later tap on the app drawer entry does: the same intent
                    // brings this task forward, and any other one stacks a second settings screen on it.
                    if (drawerEntryOn(activity)) asDrawerLaunch(settings);
                }
                activity.startActivity(settings);
            } catch (RuntimeException | LinkageError error) {
                // Messenger's first screen is a fine place to land; the restart itself already worked.
                Log.w("HushMessenger", "Couldn't reopen settings after the restart", error);
            }
        }

        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) { }
    }
}
