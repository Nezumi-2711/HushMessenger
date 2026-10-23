package app.hushmessenger.extension;

import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36}, qualifiers = "en-rUS-w390dp-h844dp-mdpi")
public class AutoReplyPictureTest {
    private static final String CHAT = "ADVANCED_CRYPTO_ONE_TO_ONE:410775001";
    private static final String REPLY_ACTION = "app.hushmessenger.test.REPLY";
    private static final String PICTURE = "PIC_1700000000000.jpg";
    private final List<Runnable> queued = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    /** What the swapped sender was handed, one "thread uri" line per send. */
    private final List<String> pictures = new ArrayList<>();
    private AutoReply.Scheduler realScheduler;
    private AutoReplyPicture.Sender realSender;
    private BooleanSupplier realBridge;
    private AutoReplyPicture.Messenger realMessenger;
    private Context context;
    private NotificationManager manager;

    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        Settings.initialize(context);
        Settings.preferences.edit().clear().commit();
        Settings.hookErrors.clear();
        realScheduler = AutoReply.scheduler;
        AutoReply.scheduler = (task, delayMs) -> {
            queued.add(task);
            delays.add(delayMs);
        };
        AutoReply.forgetQueued();
        AutoReply.clock = () -> 1_000_000;
        realSender = AutoReplyPicture.sender;
        realBridge = AutoReplyPicture.bridge;
        realMessenger = AutoReplyPicture.messenger;
        AutoReplyPicture.sender = (ctx, thread, picture, wanted) -> {
            pictures.add(thread + " " + picture);
            return null;
        };
        AutoReplyPicture.bridge = () -> true;
        Settings.preferences.edit().putBoolean(AutoReply.KEY, true).commit();
        manager = context.getSystemService(NotificationManager.class);
    }

    @After public void tidy() throws IOException {
        Dialog dialog = ShadowDialog.getLatestDialog();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        manager.cancelAll();
        AutoReply.scheduler = realScheduler;
        AutoReply.clock = android.os.SystemClock::elapsedRealtime;
        AutoReply.forgetQueued();
        AutoReplyPicture.sender = realSender;
        AutoReplyPicture.bridge = realBridge;
        AutoReplyPicture.messenger = realMessenger;
        File[] left = AutoReplyPicture.directory(context).listFiles();
        if (left != null) for (File file : left) Files.deleteIfExists(file.toPath());
    }

    private void runQueued() {
        List<Runnable> due = new ArrayList<>(queued);
        queued.clear();
        delays.clear();
        for (Runnable task : due) task.run();
    }

    /** A chat notification with a Reply action, tagged with the chat's key the way Messenger posts it. */
    private void notification(String thread) {
        RemoteInput input = new RemoteInput.Builder("reply_text").setLabel("Reply").build();
        PendingIntent intent = PendingIntent.getBroadcast(context, 0, new Intent(REPLY_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        Notification.Action reply = new Notification.Action.Builder(null, "Reply", intent).addRemoteInput(input).build();
        manager.notify(thread, thread.hashCode(), new Notification.Builder(context, "chats")
            .setSmallIcon(android.R.drawable.sym_def_app_icon).setContentTitle("Ana").addAction(reply).build());
    }

    /** What the Reply action received, or null when nothing was sent. */
    private String sent() {
        String text = null;
        for (Intent intent : shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents()) {
            if (!REPLY_ACTION.equals(intent.getAction())) continue;
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            text = results == null ? null : String.valueOf(results.getCharSequence("reply_text"));
        }
        return text;
    }

    /** A saved picture file. Its bytes only matter where something decodes it. */
    private File savedFile(String name) throws IOException {
        File file = new File(AutoReplyPicture.directory(context), name);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), new byte[] {1, 2, 3});
        return file;
    }

    /** A real picture, for the tests that decode one. */
    private File picture(File file, int width, int height, Bitmap.CompressFormat format) throws IOException {
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.rgb(200, 40, 90));
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(format, 90, output));
        }
        bitmap.recycle();
        return file;
    }

    /**
     * A picture's size and type. Robolectric's native decodeFile leaves the file open on Windows, so this reads through
     * a stream it closes, the way the app does.
     */
    private static BitmapFactory.Options bounds(File file) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (java.io.InputStream input = new java.io.FileInputStream(file)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        return bounds;
    }

    private void reply(String picture) {
        AutoReply.save(Settings.preferences, List.of(new AutoReply.Rule("", "Away", picture)), false);
    }

    /** Whether HushMessenger logged [line] as a warning. */
    private static boolean logged(String line) {
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag("HushMessenger")) {
            if (item.type == android.util.Log.WARN && line.equals(item.msg)) return true;
        }
        return false;
    }

    /**
     * Stands in for Messenger's factory and send. The factory runs [whileBuilding], then builds one message naming the
     * chat and file, and the send keeps what it was handed in [posted].
     */
    private static AutoReplyPicture.Messenger messenger(List<Object> posted, Runnable whileBuilding) {
        return new AutoReplyPicture.Messenger() {
            @Override public Object build(Context ctx, String thread, Uri picture) {
                whileBuilding.run();
                return CompletableFuture.completedFuture(List.of(thread + " " + picture));
            }

            @Override public Object send(Object message) {
                posted.add(message);
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    @Test public void aReplyKeepsItsPictureAndOnlyNamesThisAppWrites() {
        AutoReply.save(Settings.preferences, List.of(new AutoReply.Rule("hi", "Hey", PICTURE),
            new AutoReply.Rule("lunch", "At noon", "../shared_prefs/hushmessenger.xml"),
            new AutoReply.Rule("later", "Soon", "PIC_1.png"), new AutoReply.Rule("bye", "Bye")), false);
        List<AutoReply.Rule> rules = AutoReply.rules(Settings.preferences);
        assertEquals(PICTURE, rules.get(0).picture);
        assertNull(rules.get(1).picture);
        assertNull(rules.get(2).picture);
        assertNull(rules.get(3).picture);
        String json = Settings.preferences.getString(AutoReply.RULES, "");
        assertTrue(json.contains("\"picture\":\"" + PICTURE + "\""));
        assertFalse(json.contains("shared_prefs"));
        // A value edited by hand to point somewhere else reads as no picture.
        Settings.preferences.edit().putString(AutoReply.RULES, "[{\"when\":\"\",\"reply\":\"Away\",\"picture\":\"../x.jpg\"}]").commit();
        assertNull(AutoReply.rules(Settings.preferences).get(0).picture);
    }

    @Test public void thePictureFollowsTheTextOnceThroughTheSender() throws IOException {
        File file = savedFile(PICTURE);
        reply(PICTURE);
        notification(CHAT);
        AutoReply.received("hello", CHAT, "mid.1");
        runQueued();
        assertEquals("Away", sent());
        assertTrue(pictures.isEmpty());
        assertEquals(List.of(AutoReply.PICTURE_DELAY_MS), delays);
        runQueued();
        assertEquals(List.of(CHAT + " " + Uri.fromFile(file)), pictures);
        assertTrue(queued.isEmpty());
        assertEquals(0, AutoReply.pending());
    }

    @Test public void aReplyWithoutAPictureQueuesNothingAfterItsText() {
        reply(null);
        notification(CHAT);
        AutoReply.received("hello", CHAT, "mid.1");
        runQueued();
        assertEquals("Away", sent());
        assertTrue(queued.isEmpty());
    }

    @Test public void aMissingPictureStillSendsTheText() {
        reply(PICTURE);
        notification(CHAT);
        AutoReply.received("hello", CHAT, "mid.1");
        runQueued();
        assertEquals(List.of(AutoReply.PICTURE_DELAY_MS), delays);
        runQueued();
        assertEquals("Away", sent());
        assertTrue(pictures.isEmpty());
        assertTrue(logged("auto_reply: sent the text without the picture, the picture file is gone"));
        assertEquals("the picture file is gone", AutoReplyPicture.send(context, CHAT, PICTURE, () -> true));
        assertTrue(Settings.hookErrors.isEmpty());
    }

    @Test public void aBuildWithoutTheMediaSendSendsTheTextAlone() throws IOException {
        savedFile(PICTURE);
        AutoReplyPicture.bridge = () -> false;
        reply(PICTURE);
        notification(CHAT);
        AutoReply.received("hello", CHAT, "mid.1");
        runQueued();
        assertEquals(List.of(AutoReply.PICTURE_DELAY_MS), delays);
        runQueued();
        assertEquals("Away", sent());
        assertTrue(pictures.isEmpty());
        assertTrue(logged("auto_reply: sent the text without the picture, this Messenger's media send didn't match"));
        boolean preview = Settings.preview;
        try {
            Settings.preview = false;
            assertFalse(AutoReplyPicture.supported());
            AutoReplyPicture.bridge = () -> true;
            assertTrue(AutoReplyPicture.supported());
        } finally {
            Settings.preview = preview;
        }
    }

    /** The switch going off and on, or Pause, between the text and the picture drops the picture. */
    @Test public void aChangeAfterTheTextDropsThePicture() throws IOException {
        savedFile(PICTURE);
        reply(PICTURE);
        notification(CHAT);
        AutoReply.received("hello", CHAT, "mid.1");
        runQueued();
        assertEquals("Away", sent());
        assertEquals(List.of(AutoReply.PICTURE_DELAY_MS), delays);
        Settings.preferences.edit().putBoolean(AutoReply.KEY, false).commit();
        Settings.preferences.edit().putBoolean(AutoReply.KEY, true).commit();
        runQueued();
        assertTrue(pictures.isEmpty());

        AutoReply.forgetQueued();
        notification(CHAT);
        AutoReply.received("hello again", CHAT, "mid.2");
        runQueued();
        assertEquals(List.of(AutoReply.PICTURE_DELAY_MS), delays);
        Settings.preferences.edit().putBoolean("paused", true).commit();
        runQueued();
        assertTrue(pictures.isEmpty());
    }

    /** The real send hands each message Messenger's factory built to Messenger's own send. */
    @Test public void thePictureGoesThroughMessengersOwnSend() throws IOException {
        File file = savedFile(PICTURE);
        List<Object> posted = new ArrayList<>();
        AutoReplyPicture.sender = realSender;
        AutoReplyPicture.messenger = messenger(posted, () -> { });
        reply(PICTURE);
        notification(CHAT);
        AutoReply.received("hello", CHAT, "mid.1");
        runQueued();
        runQueued();
        assertEquals("Away", sent());
        assertEquals(List.of(CHAT + " " + Uri.fromFile(file)), posted);
        assertTrue(Settings.hookErrors.isEmpty());
    }

    /** Building can take seconds, so turning replies off in the meantime stops the picture before Messenger's send. */
    @Test public void turningRepliesOffWhileThePictureIsBuiltSendsNothing() throws IOException {
        savedFile(PICTURE);
        List<Object> posted = new ArrayList<>();
        AutoReplyPicture.sender = realSender;
        AutoReplyPicture.messenger = messenger(posted, () -> Settings.preferences.edit().putBoolean(AutoReply.KEY, false).commit());
        reply(PICTURE);
        notification(CHAT);
        AutoReply.received("hello", CHAT, "mid.1");
        runQueued();
        runQueued();
        assertEquals("Away", sent());
        assertTrue(posted.isEmpty());
        assertTrue(logged("auto_reply: sent the text without the picture, the auto reply changed or went off before the picture went"));
    }

    @Test public void aSenderThatFailsCostsOnlyThePicture() throws IOException {
        savedFile(PICTURE);
        AutoReplyPicture.sender = (ctx, thread, picture, wanted) -> { throw new IllegalStateException("/data/user/0/secret " + thread); };
        String why = AutoReplyPicture.send(context, CHAT, PICTURE, () -> true);
        assertEquals("sending failed with java.lang.IllegalStateException", why);
        assertEquals("the picture's name isn't one this app wrote", AutoReplyPicture.send(context, CHAT, "../" + PICTURE, () -> true));
        assertEquals("the picture's name isn't one this app wrote", AutoReplyPicture.send(context, CHAT, null, () -> true));
    }

    /** An unpatched extension has no media factory, so the real send says so instead of throwing. */
    @Test public void anUnpatchedBuildCantBuildThePictureMessage() throws IOException {
        File file = savedFile(PICTURE);
        assertEquals("Messenger didn't build the picture message",
            AutoReplyPicture.throughMessenger(context, CHAT, Uri.fromFile(file), () -> true));
    }

    @Test public void theDecodeNeverGoesUnderTheLongSide() {
        assertEquals(1, AutoReplyPicture.sample(100, 100, 2048));
        assertEquals(1, AutoReplyPicture.sample(4095, 10, 2048));
        assertEquals(2, AutoReplyPicture.sample(4096, 10, 2048));
        assertEquals(2, AutoReplyPicture.sample(3000, 5000, 2048));
        assertEquals(4, AutoReplyPicture.sample(9000, 8192, 2048));
    }

    @Test public void pruneKeepsSavedPicturesAndClearsTheRest() throws IOException {
        File kept = savedFile("PIC_1.jpg"), unused = savedFile("PIC_2.jpg"), other = savedFile("notes.txt");
        File stale = savedFile("PIC_99.part"), fresh = savedFile("PIC_98.part");
        assertTrue(stale.setLastModified(System.currentTimeMillis() - AutoReplyPicture.STALE_PARTIAL_MS - 60_000));
        AutoReplyPicture.prune(context, Set.of("PIC_1.jpg"));
        assertTrue(kept.exists());
        assertFalse(unused.exists());
        assertTrue(other.exists());
        assertFalse(stale.exists());
        assertTrue(fresh.exists());
    }

    @Test @Config(sdk = 36) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aCopyIsTurnedUprightAndNoBiggerThanTheLimit() throws IOException {
        File source = picture(new File(context.getCacheDir(), "turned.jpg"), 60, 30, Bitmap.CompressFormat.JPEG);
        ExifInterface exif = new ExifInterface(source.getPath());
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
        exif.saveAttributes();
        String name = AutoReplyPicture.copy(context, Uri.fromFile(source));
        assertTrue(AutoReplyPicture.validName(name));
        BitmapFactory.Options bounds = bounds(AutoReplyPicture.fileFor(context, name));
        assertEquals(30, bounds.outWidth);
        assertEquals(60, bounds.outHeight);

        File wide = picture(new File(context.getCacheDir(), "wide.png"), 4100, 40, Bitmap.CompressFormat.PNG);
        String shrunk = AutoReplyPicture.copy(context, Uri.fromFile(wide));
        assertNotEquals(name, shrunk);
        bounds = bounds(AutoReplyPicture.fileFor(context, shrunk));
        assertEquals(AutoReplyPicture.MAX_EDGE, bounds.outWidth);
        assertEquals("image/jpeg", bounds.outMimeType);
        String[] left = AutoReplyPicture.directory(context).list();
        assertEquals(2, left.length);
        Files.delete(source.toPath());
        Files.delete(wide.toPath());
    }

    @Test @Config(sdk = 36) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aFileThatIsntAPictureLeavesNothingBehind() throws IOException {
        File text = new File(context.getCacheDir(), "notes.jpg");
        Files.write(text.toPath(), "not a picture".getBytes(StandardCharsets.UTF_8));
        try {
            AutoReplyPicture.copy(context, Uri.fromFile(text));
            fail("copied a file that isn't a picture");
        } catch (IOException expected) {
            assertEquals("not a picture", expected.getMessage());
        }
        String[] left = AutoReplyPicture.directory(context).list();
        assertTrue(left == null || left.length == 0);
        Files.delete(text.toPath());
    }

    private View openReplies(SettingsActivity activity) {
        activity.getWindow().getDecorView().findViewWithTag("auto_reply_edit").performClick();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        return dialog.getWindow().getDecorView();
    }

    @Test @Config(sdk = 36) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aSavedPictureShowsOnItsCardAndOutlivesTheScreenBeingRebuilt() throws IOException {
        File file = picture(new File(AutoReplyPicture.directory(context), PICTURE), 80, 60, Bitmap.CompressFormat.JPEG);
        reply(PICTURE);
        Bundle state = new Bundle();
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            View root = openReplies(screen.get());
            assertEquals(View.VISIBLE, root.findViewWithTag("auto_reply_picture_thumb").getVisibility());
            assertEquals(View.VISIBLE, root.findViewWithTag("auto_reply_picture_clear").getVisibility());
            ((EditText) root.findViewWithTag("auto_reply_reply")).setText("Away for lunch");
            screen.get().onSaveInstanceState(state);
        }
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).create(state).start().resume().visible()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            View root = ShadowDialog.getLatestDialog().getWindow().getDecorView();
            assertEquals("Away for lunch", ((EditText) root.findViewWithTag("auto_reply_reply")).getText().toString());
            assertEquals(View.VISIBLE, root.findViewWithTag("auto_reply_picture_thumb").getVisibility());
            root.findViewWithTag("auto_reply_save").performClick();
        }
        AutoReply.Rule saved = AutoReply.rules(Settings.preferences).get(0);
        assertEquals("Away for lunch", saved.reply);
        assertEquals(PICTURE, saved.picture);
        assertTrue(file.exists());
    }

    @Test @Config(sdk = 36) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void removingThePictureDeletesItOnSaveAndCancelKeepsIt() throws IOException {
        File file = picture(new File(AutoReplyPicture.directory(context), PICTURE), 80, 60, Bitmap.CompressFormat.JPEG);
        reply(PICTURE);
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            View root = openReplies(screen.get());
            root.findViewWithTag("auto_reply_picture_clear").performClick();
            assertEquals(View.GONE, root.findViewWithTag("auto_reply_picture_thumb").getVisibility());
            root.findViewWithTag("auto_reply_cancel").performClick();
            // The sheet lets go of the screen when its dismiss message runs, which a phone does before the next tap.
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(PICTURE, AutoReply.rules(Settings.preferences).get(0).picture);
            assertTrue(file.exists());

            root = openReplies(screen.get());
            root.findViewWithTag("auto_reply_picture_clear").performClick();
            root.findViewWithTag("auto_reply_save").performClick();
        }
        assertNull(AutoReply.rules(Settings.preferences).get(0).picture);
        assertFalse(file.exists());
    }

    /** A build the picture send didn't match hides the picture row, and saving the replies there still keeps the picture. */
    @Test @Config(sdk = 36) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void savingWhereThePictureRowIsHiddenKeepsThePicture() throws IOException {
        File file = picture(new File(AutoReplyPicture.directory(context), PICTURE), 80, 60, Bitmap.CompressFormat.JPEG);
        reply(PICTURE);
        AutoReplyPicture.bridge = () -> false;
        boolean preview = Settings.preview;
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            // Starting the screen reads hush.preview from this build's manifest again, so it goes off after that.
            Settings.preview = false;
            assertFalse(AutoReplyPicture.supported());
            View root = openReplies(screen.get());
            assertNull(root.findViewWithTag("auto_reply_picture_thumb"));
            ((EditText) root.findViewWithTag("auto_reply_reply")).setText("Back at two");
            root.findViewWithTag("auto_reply_save").performClick();
        } finally {
            Settings.preview = preview;
        }
        AutoReply.Rule saved = AutoReply.rules(Settings.preferences).get(0);
        assertEquals("Back at two", saved.reply);
        assertEquals(PICTURE, saved.picture);
        assertTrue(file.exists());
    }

    /** With the row hidden the picture can't be seen, so a card emptied of both texts goes like any blank card. */
    @Test @Config(sdk = 36) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aBlankCardWhereThePictureRowIsHiddenSavesAway() throws IOException {
        File file = picture(new File(AutoReplyPicture.directory(context), PICTURE), 80, 60, Bitmap.CompressFormat.JPEG);
        reply(PICTURE);
        AutoReplyPicture.bridge = () -> false;
        boolean preview = Settings.preview;
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            Settings.preview = false;
            assertFalse(AutoReplyPicture.supported());
            View root = openReplies(screen.get());
            ((EditText) root.findViewWithTag("auto_reply_reply")).setText("");
            root.findViewWithTag("auto_reply_save").performClick();
            assertEquals(View.GONE, root.findViewWithTag("auto_reply_error").getVisibility());
            assertFalse(ShadowDialog.getLatestDialog().isShowing());
        } finally {
            Settings.preview = preview;
        }
        assertTrue(AutoReply.rules(Settings.preferences).isEmpty());
        assertFalse(file.exists());
    }
}
