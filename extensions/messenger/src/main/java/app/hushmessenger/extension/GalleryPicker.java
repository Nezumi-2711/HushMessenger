package app.hushmessenger.extension;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

/**
 * Opens the phone's own gallery app for the chat's gallery button. Messenger's launcher only starts its own screens, so
 * the chat starts the pick screen, which opens the gallery app and hands what the person picks back under the request
 * code Messenger reads a photo from another app under. The chat's own code then copies it and opens it in Messenger's
 * editor for that chat.
 */
final class GalleryPicker {
    static final String KEY = "external_gallery";
    private static final String[] MEDIA = {"image/*", "video/*"};

    private GalleryPicker() {}

    /** A pick for a photo or a video when some app on the phone answers it, otherwise null. */
    static Intent pickIntent(Context context) {
        if (context == null) return null;
        PackageManager packages = context.getPackageManager();
        Intent photos = new Intent(Intent.ACTION_PICK).setType("image/*").putExtra(Intent.EXTRA_MIME_TYPES, MEDIA);
        ResolveInfo opens = packages.resolveActivity(photos, PackageManager.MATCH_DEFAULT_ONLY);
        if (opens == null || opens.activityInfo == null) return null;
        // Samsung's Gallery shows only photos for a photo pick and skips the type list, but shows both for any type. Any
        // type also reaches contact pickers, so it goes only to the one gallery app Android opens without asking.
        String app = opens.activityInfo.packageName;
        boolean gallery = false;
        for (ResolveInfo candidate : packages.queryIntentActivities(photos, PackageManager.MATCH_DEFAULT_ONLY)) {
            if (candidate.activityInfo != null && app.equals(candidate.activityInfo.packageName)) gallery = true;
        }
        if (gallery) {
            Intent media = new Intent(Intent.ACTION_PICK).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, MEDIA).setPackage(app);
            if (media.resolveActivity(packages) != null) return media;
        }
        return photos;
    }

    /** The pick screen when Android knows it under this package's own name and a gallery app answers a pick, otherwise null. */
    static Intent screenIntent(Context context) {
        if (pickIntent(context) == null) return null;
        String own = context.getPackageName();
        Intent intent = new Intent().setClassName(own, GalleryActivity.class.getName());
        ResolveInfo screen = context.getPackageManager().resolveActivity(intent, 0);
        // A Root Mount install keeps the stock manifest, so the screen isn't there and Messenger's gallery stays.
        if (screen == null || screen.activityInfo == null || !own.equals(screen.activityInfo.packageName)) return null;
        return intent;
    }

    static boolean isPick(Context context, Intent intent) {
        ComponentName target = intent == null ? null : intent.getComponent();
        return context != null && target != null && context.getPackageName().equals(target.getPackageName()) &&
            GalleryActivity.class.getName().equals(target.getClassName());
    }

    /**
     * What the pick screen hands the chat for the gallery app's answer, or null for no pick. The read grant goes along, so
     * the chat can still open the item once the pick screen is gone. Gallery apps often send no type, and the chat sends
     * an item without one as a plain file, so the type comes from the item then.
     */
    static Intent handBack(Context context, Intent picked) {
        Uri item = picked == null ? null : picked.getData();
        if (item == null) return null;
        String type = picked.getType();
        if (type == null && "content".equals(item.getScheme())) type = context.getContentResolver().getType(item);
        Intent result = type == null ? new Intent().setData(item) : new Intent().setDataAndType(item, type);
        return result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
}
