package app.hushmessenger.extension;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class ExternalGalleryTest {
    static final String CAMERA_SCREEN = "com.facebook.messaging.montage.composer.MontageComposerActivity";
    Application app;

    @Before public void reset() {
        app = RuntimeEnvironment.getApplication();
        Settings.initialize(app);
        Settings.preferences.edit().clear().commit();
        Settings.activeAt.clear();
        Settings.hookErrors.clear();
        CrashGuard.resetForTests();
    }

    /** A gallery app that answers a photo pick, as Samsung's Gallery and Google Photos do. */
    private ResolveInfo installGallery() {
        ResolveInfo gallery = new ResolveInfo();
        gallery.activityInfo = new ActivityInfo();
        gallery.activityInfo.packageName = "com.example.gallery";
        gallery.activityInfo.name = "com.example.gallery.Pick";
        shadowOf(app.getPackageManager()).addResolveInfoForIntent(new Intent(Intent.ACTION_PICK).setType("image/*"), gallery);
        return gallery;
    }

    @Test public void theGalleryAppAndroidOpensOnItsOwnIsAskedForPhotosAndVideosTogether() {
        ResolveInfo gallery = installGallery();
        assertEquals("Without an any-type pick the gallery app gets a photo pick", "image/*", GalleryPicker.pickIntent(app).getType());
        // Samsung's Gallery shows videos only for an any-type pick. A contacts app answers one too, so it's asked by name.
        ResolveInfo contacts = new ResolveInfo();
        contacts.activityInfo = new ActivityInfo();
        contacts.activityInfo.packageName = "com.example.contacts";
        contacts.activityInfo.name = "com.example.contacts.Pick";
        shadowOf(app.getPackageManager()).addResolveInfoForIntent(new Intent(Intent.ACTION_PICK).setType("*/*"), contacts);
        shadowOf(app.getPackageManager()).addResolveInfoForIntent(
            new Intent(Intent.ACTION_PICK).setType("*/*").setPackage("com.example.gallery"), gallery);
        Intent pick = GalleryPicker.pickIntent(app);
        assertEquals("*/*", pick.getType());
        assertEquals("com.example.gallery", pick.getPackage());
        assertNull(pick.getComponent());
        assertEquals(Arrays.asList("image/*", "video/*"), Arrays.asList(pick.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)));
    }

    /** A gallery app as Android's own resolver needs to see it before it asks which app to use. */
    private ResolveInfo gallery(String packageName) {
        ResolveInfo gallery = new ResolveInfo();
        gallery.activityInfo = new ActivityInfo();
        gallery.activityInfo.packageName = packageName;
        gallery.activityInfo.name = packageName + ".Pick";
        gallery.activityInfo.applicationInfo = new android.content.pm.ApplicationInfo();
        gallery.activityInfo.applicationInfo.packageName = packageName;
        return gallery;
    }

    /**
     * #64: with two gallery apps and no default Android asks which one to use. The photo pick kept the contacts app off
     * that list but left Samsung's Gallery showing photos only, so the any-type pick now asks with the photo pick as the
     * selector, which Android lists the apps and records Always by.
     */
    @Test public void whenAndroidAsksWhichGalleryThePickedOneStillShowsPhotosAndVideos() {
        var packages = shadowOf(app.getPackageManager());
        packages.setShouldShowActivityChooser(true);
        ResolveInfo samsung = gallery("com.example.gallery"), photos = gallery("com.example.photos");
        Intent photoPick = new Intent(Intent.ACTION_PICK).setType("image/*");
        packages.addResolveInfoForIntent(photoPick, samsung);
        packages.addResolveInfoForIntent(photoPick, photos);
        // No contacts app here: Robolectric matches a registered pick by its action alone, so one would answer the photo
        // pick too. On a phone the contacts picker answers an any-type pick but not a photo pick, so the selector keeps it out.
        packages.addResolveInfoForIntent(new Intent(Intent.ACTION_PICK).setType("*/*").setPackage("com.example.gallery"), samsung);
        String asks = app.getPackageManager().resolveActivity(photoPick, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .activityInfo.packageName;
        assertFalse("Android picked " + asks + " without asking", Arrays.asList("com.example.gallery", "com.example.photos").contains(asks));

        assertEquals("A gallery that can't take an any-type pick keeps the photo pick", "image/*", GalleryPicker.pickIntent(app).getType());
        packages.addResolveInfoForIntent(new Intent(Intent.ACTION_PICK).setType("*/*").setPackage("com.example.photos"), photos);
        Intent pick = GalleryPicker.pickIntent(app);
        assertEquals("*/*", pick.getType());
        assertNull(pick.getPackage());
        assertNull(pick.getComponent());
        assertEquals(Arrays.asList("image/*", "video/*"), Arrays.asList(pick.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)));
        Intent selector = pick.getSelector();
        assertNotNull("Without the selector the contacts app is on the list", selector);
        assertEquals(Intent.ACTION_PICK, selector.getAction());
        assertEquals("image/*", selector.getType());
        assertNull(selector.getPackage());
    }

    /** The chat camera launcher's start: its own extras, then the ones it's handed, on a new intent for its screen. */
    private Intent launch(Bundle handed) {
        Intent intent = new Intent().setClassName(app.getPackageName(), CAMERA_SCREEN).putExtra("trigger2", "thread");
        return handed == null ? intent : intent.putExtras(handed);
    }

    @Test public void theGalleryButtonOpensThePhonesGalleryOnlyWithItsSwitchOn() {
        installGallery();
        assertNull("Off leaves the button to Messenger", Settings.chatGallery());
        assertEquals(0, Settings.lastActive(GalleryPicker.KEY));

        Settings.preferences.edit().putBoolean(GalleryPicker.KEY, true).commit();
        Bundle handed = Settings.chatGallery();
        assertNotNull(handed);
        assertTrue(Settings.lastActive(GalleryPicker.KEY) > 0);
        Intent marked = launch(handed);
        marked.putExtra(Settings.CHAT_CAMERA_MARK, true);
        Intent screen = Settings.chatCamera(marked);
        // Messenger's launcher refuses an intent for another app's screen, so the button starts the pick screen inside it.
        assertEquals(new ComponentName(app.getPackageName(), GalleryActivity.class.getName()), screen.getComponent());
        assertNull(screen.getAction());
        assertEquals("The chat reads the pick back the way it reads a photo picked in another app", 1112, Settings.cameraRequestCode(screen, 7376));
        assertFalse(marked.hasExtra(Settings.CHAT_GALLERY_MARK));
        assertFalse(marked.hasExtra(Settings.CHAT_CAMERA_MARK));
        Intent pick = GalleryPicker.pickIntent(app);
        assertEquals(Intent.ACTION_PICK, pick.getAction());
        assertEquals("image/*", pick.getType());
        assertNull("The pick goes to whichever gallery app the person uses", pick.getComponent());
        assertEquals(Arrays.asList("image/*", "video/*"), Arrays.asList(pick.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)));

        // The camera button's next launch is built fresh, without the gallery's extras, and stays Messenger's camera.
        Intent camera = launch(null);
        assertSame(camera, Settings.chatCamera(camera));
        assertEquals(7376, Settings.cameraRequestCode(camera, 7376));

        Settings.preferences.edit().putBoolean("paused", true).commit();
        assertNull("Pause brings Messenger's gallery back", Settings.chatGallery());
        assertTrue(Settings.hookErrors.isEmpty());
    }

    @Test public void withNoGalleryAppTheSwitchSaysWhyAndTheButtonStaysMessengers() {
        Settings.preferences.edit().putBoolean(GalleryPicker.KEY, true).commit();
        assertNull(GalleryPicker.pickIntent(app));
        assertFalse(Settings.available(GalleryPicker.KEY));
        assertNull(Settings.chatGallery());
        assertEquals(0, Settings.lastActive(GalleryPicker.KEY));

        // A gallery app removed between the tap and the launch leaves the launcher's own screen, marks off.
        Intent marked = launch(null).putExtra(Settings.CHAT_GALLERY_MARK, true);
        assertSame(marked, Settings.chatCamera(marked));
        assertFalse(marked.hasExtra(Settings.CHAT_GALLERY_MARK));
        assertEquals("thread", marked.getStringExtra("trigger2"));
        assertEquals(7376, Settings.cameraRequestCode(marked, 7376));

        installGallery();
        assertTrue(Settings.available(GalleryPicker.KEY));
        assertTrue(Settings.hookErrors.isEmpty());
    }

    /** A gallery app's provider, which knows each picked item's type. */
    public static class PickedProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return uri.getPath().startsWith("/video") ? "video/mp4" : "image/jpeg"; }
        @Override public Cursor query(Uri uri, String[] columns, String selection, String[] arguments, String order) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] arguments) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) { return 0; }
    }

    @Test public void aPickWithNoTypeGetsItsOwnTypeSoTheChatOpensItInTheEditor() {
        String authority = "com.example.gallery.picked";
        Robolectric.setupContentProvider(PickedProvider.class, authority);
        Uri photo = Uri.parse("content://" + authority + "/image/7");
        Intent off = new Intent().setData(photo);
        Settings.externalMediaResult(1112, Activity.RESULT_OK, off);
        assertNull("Off leaves the result as the gallery app sent it", off.getType());

        Settings.preferences.edit().putBoolean(GalleryPicker.KEY, true).commit();
        Intent picked = new Intent().setData(photo).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Settings.externalMediaResult(1112, Activity.RESULT_OK, picked);
        assertEquals("image/jpeg", picked.getType());
        assertEquals(photo, picked.getData());
        assertTrue("The read grant stays on the result", (picked.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        Intent video = new Intent().setData(Uri.parse("content://" + authority + "/video/8"));
        Settings.externalMediaResult(1112, Activity.RESULT_OK, video);
        assertEquals("video/mp4", video.getType());

        // A type the app sent, another request, a cancel and a file path stay as they are.
        Intent typed = new Intent().setDataAndType(photo, "image/png");
        Settings.externalMediaResult(1112, Activity.RESULT_OK, typed);
        assertEquals("image/png", typed.getType());
        Intent camera = new Intent().setData(photo);
        Settings.externalMediaResult(7376, Activity.RESULT_OK, camera);
        assertNull(camera.getType());
        Intent cancelled = new Intent().setData(photo);
        Settings.externalMediaResult(1112, Activity.RESULT_CANCELED, cancelled);
        assertNull(cancelled.getType());
        Intent file = new Intent().setData(Uri.parse("file:///sdcard/DCIM/a.jpg"));
        Settings.externalMediaResult(1112, Activity.RESULT_OK, file);
        assertNull(file.getType());
        Settings.externalMediaResult(1112, Activity.RESULT_OK, null);

        Settings.preferences.edit().putBoolean("paused", true).commit();
        Intent paused = new Intent().setData(photo);
        Settings.externalMediaResult(1112, Activity.RESULT_OK, paused);
        assertNull("Pause leaves it to Messenger", paused.getType());
        assertTrue(Settings.hookErrors.isEmpty());
    }

    @Test public void onlyThePickScreenGetsThePickedPhotoRequestCode() {
        Intent someoneElses = new Intent(Intent.ACTION_PICK).setType("image/*");
        assertFalse(GalleryPicker.isPick(app, someoneElses));
        assertEquals(7376, Settings.cameraRequestCode(someoneElses, 7376));
        Intent copied = new Intent().setClassName("com.facebook.orca.other", GalleryActivity.class.getName());
        assertFalse(GalleryPicker.isPick(app, copied));
        assertFalse(GalleryPicker.isPick(app, null));
        assertFalse(GalleryPicker.isPick(null, new Intent().setClassName(app, GalleryActivity.class.getName())));
        assertEquals(7376, Settings.cameraRequestCode(null, 7376));
    }

    @Test public void thePickScreenHandsWhatThePersonPickedBackToTheChatWithItsTypeAndReadGrant() {
        installGallery();
        String authority = "com.example.gallery.picked";
        Robolectric.setupContentProvider(PickedProvider.class, authority);
        for (String path : new String[] {"/image/7", "/video/8"}) {
            Uri item = Uri.parse("content://" + authority + path);
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class).setup()) {
                ShadowActivity screen = shadowOf(controller.get());
                Intent pick = screen.getNextStartedActivityForResult().intent;
                assertEquals(Intent.ACTION_PICK, pick.getAction());
                assertEquals(Arrays.asList("image/*", "video/*"), Arrays.asList(pick.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)));
                screen.receiveResult(pick, Activity.RESULT_OK, new Intent().setData(item).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                assertTrue(controller.get().isFinishing());
                assertEquals(Activity.RESULT_OK, screen.getResultCode());
                Intent result = screen.getResultIntent();
                assertEquals(item, result.getData());
                assertEquals(path.startsWith("/video") ? "video/mp4" : "image/jpeg", result.getType());
                // The chat gets its own read grant, so the photo still opens after this screen is gone.
                assertNotEquals(0, result.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        }
        // A type the gallery app sent stays.
        Uri photo = Uri.parse("content://" + authority + "/image/9");
        Intent typed = GalleryPicker.handBack(app, new Intent().setDataAndType(photo, "image/png"));
        assertEquals("image/png", typed.getType());
        assertNull(GalleryPicker.handBack(app, new Intent()));
        assertNull(GalleryPicker.handBack(app, null));
        assertTrue(Settings.hookErrors.isEmpty());
    }

    @Test public void aCancelledPickLeavesTheChatAsItWas() {
        installGallery();
        try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class).setup()) {
            ShadowActivity screen = shadowOf(controller.get());
            Intent pick = screen.getNextStartedActivityForResult().intent;
            screen.receiveResult(pick, Activity.RESULT_CANCELED, null);
            assertTrue(controller.get().isFinishing());
            assertEquals(Activity.RESULT_CANCELED, screen.getResultCode());
            assertNull(screen.getResultIntent());
        }
        // A gallery app that says OK but hands nothing back is a cancel too.
        try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class).setup()) {
            ShadowActivity screen = shadowOf(controller.get());
            screen.receiveResult(screen.getNextStartedActivityForResult().intent, Activity.RESULT_OK, new Intent());
            assertEquals(Activity.RESULT_CANCELED, screen.getResultCode());
        }
        assertTrue(Settings.hookErrors.isEmpty());
    }

    @Test public void withTheGalleryAppGoneThePickScreenSaysSoAndCloses() {
        try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class).setup()) {
            ShadowActivity screen = shadowOf(controller.get());
            assertNull(screen.getNextStartedActivityForResult());
            assertTrue(controller.get().isFinishing());
            assertEquals(Activity.RESULT_CANCELED, screen.getResultCode());
            assertEquals(new SettingsText(app).get("gallery_missing"), ShadowToast.getTextOfLatestToast());
        }
    }

    /** #64 and #82: the gallery app lets the person pick several photos and videos at once, in either pick. */
    @Test public void bothPicksLetThePersonTakeSeveralItems() {
        ResolveInfo gallery = installGallery();
        assertTrue(GalleryPicker.pickIntent(app).getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
        shadowOf(app.getPackageManager()).addResolveInfoForIntent(
            new Intent(Intent.ACTION_PICK).setType("*/*").setPackage("com.example.gallery"), gallery);
        Intent anyType = GalleryPicker.pickIntent(app);
        assertEquals("*/*", anyType.getType());
        assertTrue(anyType.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
    }

    private static android.content.ClipData clipOf(Uri... items) {
        android.content.ClipData clip = android.content.ClipData.newRawUri("picked", items[0]);
        for (int i = 1; i < items.length; i++) clip.addItem(new android.content.ClipData.Item(items[i]));
        return clip;
    }

    private static Intent withClip(Intent intent, Uri... items) {
        intent.setClipData(clipOf(items));
        return intent;
    }

    private static java.util.List<Uri> urisOf(android.content.ClipData clip) {
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        return uris;
    }

    @Test public void severalPickedItemsComeBackTogetherMarkedWithTheFirstAsTheResult() {
        String authority = "com.example.gallery.picked";
        Robolectric.setupContentProvider(PickedProvider.class, authority);
        Uri photo = Uri.parse("content://" + authority + "/image/7"), video = Uri.parse("content://" + authority + "/video/8");
        // Samsung's Gallery sends a pick of several in the clip alone, and some apps put the first in the data too.
        for (Intent picked : new Intent[] {new Intent(), new Intent().setData(photo)}) {
            picked.setClipData(clipOf(photo, video));
            Intent result = GalleryPicker.handBack(app, picked);
            assertEquals(photo, result.getData());
            assertEquals("image/jpeg", result.getType());
            assertEquals(Arrays.asList(photo, video), urisOf(result.getClipData()));
            assertEquals(Arrays.asList("image/jpeg", "video/mp4"),
                Arrays.asList(result.getClipData().getDescription().getMimeType(0), result.getClipData().getDescription().getMimeType(1)));
            assertTrue(result.getBooleanExtra(GalleryPicker.ITEMS_MARK, false));
            assertNotEquals(0, result.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        // One item in the clip alone is an ordinary pick, so Messenger's editor opens it as before.
        Intent single = GalleryPicker.handBack(app, withClip(new Intent(), video));
        assertEquals(video, single.getData());
        assertEquals("video/mp4", single.getType());
        assertNull(single.getClipData());
        assertFalse(single.hasExtra(GalleryPicker.ITEMS_MARK));
        // The same item twice is one item.
        Intent twice = GalleryPicker.handBack(app, withClip(new Intent().setData(photo), photo));
        assertNull(twice.getClipData());
        assertFalse(twice.hasExtra(GalleryPicker.ITEMS_MARK));
        assertTrue(Settings.hookErrors.isEmpty());
    }

    @Test public void thePickScreenHandsSeveralItemsBackToTheChat() {
        installGallery();
        String authority = "com.example.gallery.picked";
        Robolectric.setupContentProvider(PickedProvider.class, authority);
        Uri photo = Uri.parse("content://" + authority + "/image/7"), video = Uri.parse("content://" + authority + "/video/8");
        try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class).setup()) {
            ShadowActivity screen = shadowOf(controller.get());
            Intent pick = screen.getNextStartedActivityForResult().intent;
            screen.receiveResult(pick, Activity.RESULT_OK, withClip(new Intent(), photo, video).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            assertEquals(Activity.RESULT_OK, screen.getResultCode());
            Intent result = screen.getResultIntent();
            assertEquals(photo, result.getData());
            assertEquals(Arrays.asList(photo, video), urisOf(result.getClipData()));
            assertTrue(result.getBooleanExtra(GalleryPicker.ITEMS_MARK, false));
            assertNotEquals(0, result.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    /** The chat's photo handler with a marked pick of several: Messenger's Send to sheet, with the chat on top. */
    @Test public void aPickOfSeveralGoesToTheSendToSheetWithTheChatOnTop() {
        String authority = "com.example.gallery.picked";
        Robolectric.setupContentProvider(PickedProvider.class, authority);
        Uri photo = Uri.parse("content://" + authority + "/image/7"), video = Uri.parse("content://" + authority + "/video/8");
        Uri png = Uri.parse("content://" + authority + "/image/9");
        Uri thread = Uri.parse("thread://100000000000042");
        var started = shadowOf(app);
        Intent mixed = GalleryPicker.handBack(app, withClip(new Intent(), photo, video));
        assertFalse("Off leaves the pick to Messenger", Settings.galleryItems(thread, mixed));
        assertNull(started.getNextStartedActivity());

        Settings.preferences.edit().putBoolean(GalleryPicker.KEY, true).commit();
        assertTrue(Settings.galleryItems(thread, mixed));
        assertTrue(Settings.lastActive(GalleryPicker.KEY) > 0);
        Intent send = started.getNextStartedActivity();
        assertEquals(new ComponentName(app.getPackageName(), GalleryPicker.SHARE_HANDLER), send.getComponent());
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.getAction());
        assertEquals("*/*", send.getType());
        assertEquals(Arrays.asList(photo, video), send.getParcelableArrayListExtra(Intent.EXTRA_STREAM));
        assertEquals(Arrays.asList(photo, video), urisOf(send.getClipData()));
        assertEquals(Arrays.asList(thread), send.getParcelableArrayListExtra(GalleryPicker.SHARE_TARGETS));
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK;
        assertEquals(flags, send.getFlags() & flags);
        // Messenger's second caller with the same result takes it without a second sheet.
        assertTrue(Settings.galleryItems(thread, mixed));
        assertNull(started.getNextStartedActivity());

        // All photos is a photo share, and a thread that can't ride along leaves the sheet without a pinned chat.
        for (Object noPin : new Object[] {null, "100000000000042"}) {
            Intent photos = GalleryPicker.handBack(app, withClip(new Intent(), photo, png));
            assertTrue(Settings.galleryItems(noPin, photos));
            Intent unpinned = started.getNextStartedActivity();
            assertEquals("image/*", unpinned.getType());
            assertFalse(unpinned.hasExtra(GalleryPicker.SHARE_TARGETS));
        }

        // One item, an unmarked clip, a cancel and Pause stay with Messenger's editor.
        Intent single = GalleryPicker.handBack(app, new Intent().setData(photo));
        Intent unmarked = withClip(new Intent().setData(photo), photo, video);
        for (Intent stock : new Intent[] {single, unmarked, null}) {
            assertFalse(Settings.galleryItems(thread, stock));
            assertNull(started.getNextStartedActivity());
        }
        Settings.preferences.edit().putBoolean("paused", true).commit();
        assertFalse("Pause leaves the pick to Messenger", Settings.galleryItems(thread, mixed));
        assertNull(started.getNextStartedActivity());
        assertTrue(Settings.hookErrors.isEmpty());
    }
}
