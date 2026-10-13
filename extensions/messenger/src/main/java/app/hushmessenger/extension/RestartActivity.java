package app.hushmessenger.extension;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Reopen the real launcher in a fresh process after pending settings writes finish. */
public class RestartActivity extends Activity {
    @Override @SuppressWarnings("deprecation") public void onCreate(Bundle state) {
        // On a Root Mount install this screen can be the first thing in the process, before any hook.
        HostScreens.start(this);
        Settings.initialize(this);
        boolean light = Settings.preferences.getBoolean("light", false);
        setTheme(light ? android.R.style.Theme_Material_Light_NoActionBar : android.R.style.Theme_Material_NoActionBar);
        super.onCreate(state);
        SettingsUi ui = new SettingsUi(this, light);
        getWindow().setStatusBarColor(ui.background);
        getWindow().setNavigationBarColor(ui.background);
        getWindow().getDecorView().setSystemUiVisibility(light ? android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        setContentView(content(ui));
        Intent launch = launcherIntent(this);
        if (launch == null) {
            fail("restart_unavailable");
            return;
        }
        new Thread(() -> {
            boolean saved = saveBeforeRestart(Settings.preferences);
            runOnUiThread(() -> completeRestart(launch, saved));
        }, "HushMessenger-save-restart").start();
    }

    /** A scrollable status pane also fits large text and a short split-screen window. */
    static View content(SettingsUi ui) {
        SettingsText text = new SettingsText(ui.context);
        LinearLayout root = ui.column();
        root.setTag("restart_page");
        root.setBackgroundColor(ui.background);
        root.setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24));
        root.setLayoutDirection(text.layoutDirection());
        if (text.layoutDirection() == View.LAYOUT_DIRECTION_RTL) root.setTextDirection(View.TEXT_DIRECTION_RTL);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            DisplayCutout cutout = insets.getDisplayCutout();
            int left = insets.getSystemWindowInsetLeft(), top = insets.getSystemWindowInsetTop();
            int right = insets.getSystemWindowInsetRight(), bottom = insets.getSystemWindowInsetBottom();
            if (cutout != null) {
                left = Math.max(left, cutout.getSafeInsetLeft());
                top = Math.max(top, cutout.getSafeInsetTop());
                right = Math.max(right, cutout.getSafeInsetRight());
                bottom = Math.max(bottom, cutout.getSafeInsetBottom());
            }
            root.setPadding(ui.dp(24) + left, ui.dp(24) + top, ui.dp(24) + right, ui.dp(24) + bottom);
            return insets.consumeSystemWindowInsets().consumeDisplayCutout();
        });
        ScrollView scroll = new ScrollView(ui.context);
        scroll.setFillViewport(true);
        FrameLayout center = new FrameLayout(ui.context);
        LinearLayout message = ui.column();
        message.setGravity(Gravity.CENTER);
        message.setPadding(ui.dp(24), ui.dp(32), ui.dp(24), ui.dp(32));
        message.setBackground(ui.shape(ui.surface, ui.line, 12));
        TextView brand = ui.heading("HushMessenger");
        brand.setGravity(Gravity.CENTER);
        brand.setTextSize(11);
        brand.setLetterSpacing(0.12f);
        message.addView(brand);
        View accent = new View(ui.context);
        accent.setBackground(ui.shape(ui.accent, 0, 2));
        accent.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(ui.dp(56), ui.dp(4));
        accentParams.topMargin = ui.dp(20);
        message.addView(accent, accentParams);
        TextView status = ui.text(text.get("restarting"), 24, ui.text, true);
        status.setTag("restart_status");
        status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ui.add(message, status, 24);
        TextView detail = ui.text(text.get("restart_saving"), 14, ui.muted, false);
        detail.setTag("restart_detail");
        detail.setGravity(Gravity.CENTER);
        detail.setMaxWidth(ui.dp(220));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-2, -2);
        detailParams.topMargin = ui.dp(16);
        message.addView(detail, detailParams);
        ProgressBar progress = new ProgressBar(ui.context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setTag("restart_progress");
        progress.setIndeterminate(true);
        progress.setBackground(ui.shape(ui.line, 0, 2));
        progress.setIndeterminateTintList(ColorStateList.valueOf(ui.accent));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(ui.line));
        progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, ui.dp(4));
        progressParams.setMarginStart(ui.dp(12));
        progressParams.setMarginEnd(ui.dp(12));
        progressParams.topMargin = ui.dp(32);
        message.addView(progress, progressParams);
        center.addView(message, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        scroll.addView(center);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView account = ui.text(text.get("restart_account"), 12, ui.muted, false);
        account.setGravity(Gravity.CENTER);
        ui.add(root, account, 24);
        return root;
    }

    static Intent launcherIntent(Context context) {
        String packageName = context.getPackageName();
        PackageManager packages = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName);
        for (ResolveInfo match : packages.queryIntentActivities(query, 0)) {
            var activity = match.activityInfo;
            if (activity == null || !packageName.equals(activity.packageName) || activity.name == null ||
                activity.name.startsWith("app.hushmessenger.extension.") ||
                (activity.targetActivity != null && activity.targetActivity.startsWith("app.hushmessenger.extension.")) ||
                !enabledNow(packages, packageName, activity)) continue;
            return Intent.makeRestartActivityTask(new ComponentName(packageName, activity.name)).setPackage(packageName);
        }
        return null;
    }

    /**
     * ActivityInfo.enabled is the manifest value. Alternate app icons are manifest-disabled aliases
     * that Messenger enables at runtime, so the runtime state decides and the manifest only backs DEFAULT.
     */
    static boolean enabledNow(PackageManager packages, String packageName, ActivityInfo activity) {
        int state;
        try { state = packages.getComponentEnabledSetting(new ComponentName(packageName, activity.name)); }
        // queryIntentActivities without MATCH_DISABLED_COMPONENTS has already left out disabled components.
        catch (IllegalArgumentException | SecurityException unknown) { return true; }
        if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
        if (state != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) return false;
        return activity.applicationInfo != null ? activity.isEnabled() : activity.enabled;
    }

    static boolean saveBeforeRestart(SharedPreferences preferences) {
        try {
            // A changed value forces a disk write of the latest choices, including pending apply() calls.
            return preferences != null && preferences.edit().putLong("restart_requested_at", System.nanoTime()).commit();
        } catch (RuntimeException error) {
            Log.e("HushMessenger", "Couldn't save settings before restart", error);
            return false;
        }
    }

    void completeRestart(Intent launch, boolean saved) {
        if (isFinishing() || isDestroyed()) return;
        if (!saved) {
            fail("restart_save_failed");
            return;
        }
        try {
            startActivity(launch);
            finish();
            exitProcess();
        } catch (android.content.ActivityNotFoundException | SecurityException error) {
            Log.e("HushMessenger", "Couldn't restart Messenger", error);
            fail("restart_unavailable");
        }
    }

    void exitProcess() { System.exit(0); }

    private void fail(String message) {
        Log.e("HushMessenger", message);
        Toast.makeText(this, new SettingsText(this).get(message), Toast.LENGTH_LONG).show();
        finish();
    }
}
