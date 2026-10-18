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
}
