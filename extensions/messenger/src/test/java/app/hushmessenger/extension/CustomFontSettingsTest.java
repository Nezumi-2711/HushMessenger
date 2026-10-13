package app.hushmessenger.extension;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * The five entry points the custom_font hooks call, and the font file rows under its switch. Off, paused or before the
 * settings load, every typeface is Messenger's own. Emoji are never touched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CustomFontSettingsTest {
    private static final OwnFontTest.Family BOLD = OwnFontTest.Family.OPTIMISTIC_TEXT_APP_BOLD;
    private static final String GRINNING = "😀";

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Before public void reset() throws Exception {
        FontFile.testFolder = folder.newFolder();
        OwnFont.fileCheck = OwnFontTest::loadsCopy;
        Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("hushmessenger", Context.MODE_PRIVATE).edit().clear().commit();
        Settings.initialize(app);
        Settings.activeAt.clear();
        Settings.hookErrors.clear();
        CrashGuard.resetForTests();
        Settings.customFont = null;
        OwnFont.forget();
    }

    @After public void restore() {
        Settings.preferences.edit().clear().commit();
        Settings.customFont = null;
        OwnFont.forget();
        FontFile.testFolder = null;
        OwnFont.fileCheck = OwnFont::loads;
    }

    private static void switchOn() {
        Settings.preferences.edit().putBoolean(OwnFont.KEY, true).commit();
    }

    private static Typeface emoji() throws Exception {
        File file = new File(RuntimeEnvironment.getApplication().getCacheDir(), "NotoColorEmoji.ttf");
        Files.write(file.toPath(), FontFileTest.font("fonts/NotoColorEmoji.ttf"));
        return new Typeface.Builder(file).build();
    }

    @Test public void offEveryTypefaceStaysMessengers() throws Exception {
        // A picked file changes nothing while the switch is off.
        OwnFontTest.pick(FontFileTest.STATIC_FONT, "Rubik-Regular.ttf");
        Context app = RuntimeEnvironment.getApplication();
        Typeface meta = Typeface.create(Typeface.SERIF, 700, false);
        Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
        assertSame(meta, Settings.customFontRepository(meta, BOLD, 700));
        assertSame(regular, Settings.customFontLayout(regular));
        assertNull(Settings.customFontLayout(null));
        assertSame(regular, Settings.customFontRoboto(regular));
        assertSame(regular, Settings.customFontByName(regular, "sans-serif"));
        TextView view = new TextView(app);
        Settings.customFontInput(view, regular, Typeface.NORMAL);
        assertSame(regular, view.getTypeface());
        assertEquals(Boolean.FALSE, Settings.customFont);
        assertEquals(0, Settings.lastActive(OwnFont.KEY));
    }

    @Test public void pausedOrNotInstalledAtTheFirstAskMeansOff() {
        Typeface meta = Typeface.create(Typeface.SERIF, 700, false);
        Settings.preferences.edit().putBoolean(OwnFont.KEY, true).putBoolean("paused", true).commit();
        assertSame(meta, Settings.customFontRepository(meta, BOLD, 700));
        Settings.customFont = null;
        Settings.preferences.edit().putBoolean("paused", false).commit();
        Settings.installed = java.util.Collections.emptySet();
        assertSame(meta, Settings.customFontRepository(meta, BOLD, 700));
        assertEquals(Boolean.FALSE, Settings.customFont);
    }

    @Test public void nothingIsHeldBeforeTheSettingsLoad() {
        switchOn();
        Typeface meta = Typeface.create(Typeface.SERIF, 700, false);
        android.content.SharedPreferences loaded = Settings.preferences;
        boolean started = HostScreens.started;
        // Started, so the hook can't load the settings itself and has to answer without them.
        HostScreens.started = true;
        Settings.preferences = null;
        try {
            assertSame(meta, Settings.customFontRepository(meta, BOLD, 700));
            assertNull(Settings.customFont);
            Settings.preferences = loaded;
            assertNotSame(meta, Settings.customFontRepository(meta, BOLD, 700));
            assertEquals(Boolean.TRUE, Settings.customFont);
        } finally {
            Settings.preferences = loaded;
            HostScreens.started = started;
        }
    }

    @Test public void onOptimisticBecomesThePhonesFontAndRobotoMonoStays() {
        switchOn();
        Typeface meta = Typeface.create(Typeface.SERIF, 400, false);
        assertSame(Typeface.create(Typeface.DEFAULT, 700, false), Settings.customFontRepository(meta, BOLD, 700));
        assertTrue(Settings.lastActive(OwnFont.KEY) > 0);
        Settings.activeAt.clear();

        Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
        assertSame(mono, Settings.customFontRepository(mono, OwnFontTest.Family.ROBOTO_MONO_REGULAR, 400));
        assertSame("only the enum's constant names a family", mono, Settings.customFontRepository(mono, BOLD.name(), 400));
        assertSame(mono, Settings.customFontRepository(mono, null, 400));
        // With no file picked, the phone's font is the phone's font already.
        Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
        assertSame(regular, Settings.customFontLayout(regular));
        assertNull(Settings.customFontLayout(null));
        assertSame(regular, Settings.customFontRoboto(regular));
        assertSame(regular, Settings.customFontByName(regular, "sans-serif"));
        assertEquals("nothing changed, so nothing counts as a use", 0, Settings.lastActive(OwnFont.KEY));
    }

    @Test public void aPickedFileReplacesThePhonesFontWhereverMessengerAsks() throws Exception {
        switchOn();
        OwnFontTest.pick(FontFileTest.STATIC_FONT, "Rubik-Regular.ttf");
        Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        assertSame(OwnFont.typeface(400, false), Settings.customFontLayout(regular));
        assertSame("none means Android's default", OwnFont.typeface(400, false), Settings.customFontLayout(null));
        assertSame(OwnFont.typeface(medium.getWeight(), false), Settings.customFontRoboto(medium));
        assertSame(OwnFont.typeface(medium.getWeight(), false), Settings.customFontByName(medium, "sans-serif-medium"));
        assertSame(OwnFont.typeface(700, false), Settings.customFontRepository(Typeface.create(Typeface.SERIF, 400, false), BOLD, 700));
        assertTrue(Settings.lastActive(OwnFont.KEY) > 0);

        Typeface serif = Typeface.create("serif", Typeface.NORMAL);
        assertSame("another family by name stays", serif, Settings.customFontByName(serif, "serif"));
        assertNull(Settings.customFontByName(null, "sans-serif"));
        assertNull(Settings.customFontRoboto(null));
        Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
        assertSame(mono, Settings.customFontRepository(mono, OwnFontTest.Family.ROBOTO_MONO_REGULAR, 400));

        TextView view = new TextView(RuntimeEnvironment.getApplication());
        Settings.customFontInput(view, null, Typeface.BOLD);
        assertSame("the input's style still applies", Typeface.create(OwnFont.typeface(400, false), Typeface.BOLD), view.getTypeface());
        Settings.customFontInput(view, mono, Typeface.NORMAL);
        assertSame(mono, view.getTypeface());
    }

    @Test public void emojiStayTheirOwnWithBothSwitchesOn() throws Exception {
        Settings.preferences.edit().putBoolean(OwnFont.KEY, true).putBoolean("use_system_emoji", true).commit();
        OwnFontTest.pick(FontFileTest.STATIC_FONT, "Rubik-Regular.ttf");
        Typeface emoji = emoji();
        assertSame(emoji, Settings.customFontLayout(emoji));
        assertSame(emoji, Settings.customFontRoboto(emoji));
        assertSame(emoji, Settings.customFontByName(emoji, "Noto Color Emoji"));
        TextView view = new TextView(RuntimeEnvironment.getApplication());
        Settings.customFontInput(view, emoji, Typeface.NORMAL);
        assertSame(emoji, view.getTypeface());

        // A picked file keeps Android's fallback, so an emoji in text drawn with it still has its glyph.
        Paint phone = new Paint();
        assumeTrue("this runtime draws no emoji at all", phone.hasGlyph(GRINNING));
        Paint picked = new Paint();
        picked.setTypeface(Settings.customFontLayout(Typeface.DEFAULT));
        assertNotSame(Typeface.DEFAULT, picked.getTypeface());
        assertTrue(picked.hasGlyph(GRINNING));
    }

    @Test public void theSwitchHoldsItsFirstAnswerUntilARestart() {
        switchOn();
        assertNull("nothing is held before Messenger asks", Settings.heldUntilRestart(OwnFont.KEY));
        Typeface meta = Typeface.create(Typeface.SERIF, 400, false);
        Typeface swapped = Settings.customFontRepository(meta, OwnFontTest.Family.OPTIMISTIC_TEXT_APP_REGULAR, 400);
        assertNotSame(meta, swapped);
        assertNull(Settings.heldUntilRestart(OwnFont.KEY));

        Settings.preferences.edit().putBoolean(OwnFont.KEY, false).commit();
        assertSame(swapped, Settings.customFontRepository(meta, OwnFontTest.Family.OPTIMISTIC_TEXT_APP_REGULAR, 400));
        assertEquals(Boolean.TRUE, Settings.heldUntilRestart(OwnFont.KEY));

        Settings.customFont = null;
        assertSame("a restart asks again", meta, Settings.customFontRepository(meta, OwnFontTest.Family.OPTIMISTIC_TEXT_APP_REGULAR, 400));
        switchOn();
        assertEquals(Boolean.FALSE, Settings.heldUntilRestart(OwnFont.KEY));
    }

    @Test public void theControlSitsUnderThemeAndSaysEmojiDontChange() {
        String[] row = java.util.Arrays.stream(SettingsActivity.CONTROLS).filter(c -> c[0].equals(OwnFont.KEY)).findFirst().orElseThrow();
        assertEquals("Use your own font", row[1]);
        assertEquals("theme", row[3]);
        assertTrue(row[2].contains("Emoji don't change."));
        assertTrue(row[2].contains("Restart Messenger"));
        assertTrue(Settings.installed.contains(OwnFont.KEY));
    }

    @Test public void theFontRowsFollowThePickedFile() throws Exception {
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            SettingsActivity activity = screen.get();
            View root = activity.getWindow().getDecorView();
            TextView status = root.findViewWithTag("font_file_status");
            // With the switch off nothing is in use yet, so the line says what turning it on would do.
            assertEquals("None chosen. Turn the switch on to use your phone's font, or choose a .ttf or .otf font file of up to 20 MB.", status.getText().toString());
            assertEquals(View.GONE, root.findViewWithTag("font_phone").getVisibility());

            root.findViewWithTag("font_file").performClick();
            var request = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            assertEquals(SettingsActivity.PICK_FONT, request.requestCode);
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.getAction());
            assertTrue(request.intent.hasCategory(Intent.CATEGORY_OPENABLE));
            assertEquals("*/*", request.intent.getType());
            assertArrayEquals(SettingsActivity.FONT_TYPES, request.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));

            activity.onActivityResult(SettingsActivity.PICK_FONT, Activity.RESULT_CANCELED, null);
            assertEquals("", Settings.preferences.getString(OwnFont.NAME_KEY, ""));
            activity.onActivityResult(SettingsActivity.PICK_FONT, Activity.RESULT_OK, new Intent().setData(Uri.parse("file:///sdcard/Rubik.ttf")));
            assertEquals("A file path isn't a picker's grant", "Couldn't open that file. Your font didn't change.", ShadowToast.getTextOfLatestToast());

            Uri photo = Uri.parse("content://fonts/picked/photo.png");
            Shadows.shadowOf(activity.getContentResolver()).registerInputStream(photo,
                new ByteArrayInputStream(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0}));
            activity.onActivityResult(SettingsActivity.PICK_FONT, Activity.RESULT_OK, new Intent().setData(photo));
            awaitToast("That isn't a .ttf or .otf font file. Your font didn't change.");
            assertEquals("", Settings.preferences.getString(OwnFont.NAME_KEY, ""));

            Uri rubik = Uri.parse("content://fonts/picked/Rubik-Regular.ttf");
            Shadows.shadowOf(activity.getContentResolver()).registerInputStream(rubik,
                new ByteArrayInputStream(FontFileTest.font(FontFileTest.STATIC_FONT)));
            activity.onActivityResult(SettingsActivity.PICK_FONT, Activity.RESULT_OK, new Intent().setData(rubik));
            awaitToast("Font set to Rubik-Regular.ttf. Restart Messenger to see it.");
            assertEquals("Rubik-Regular.ttf", Settings.preferences.getString(OwnFont.NAME_KEY, ""));
            assertArrayEquals(FontFileTest.font(FontFileTest.STATIC_FONT), Files.readAllBytes(FontFile.file(activity).toPath()));
            assertEquals("Rubik-Regular.ttf is picked. Turn the switch on to use it.", status.getText().toString());
            assertEquals(View.VISIBLE, root.findViewWithTag("font_phone").getVisibility());
            assertTrue(root.findViewWithTag("font_file").isEnabled());
            ((android.widget.CompoundButton) root.findViewWithTag(OwnFont.KEY)).setChecked(true);
            assertEquals("Using Rubik-Regular.ttf. Choose another file to replace it.", status.getText().toString());
            ((android.widget.CompoundButton) root.findViewWithTag(OwnFont.KEY)).setChecked(false);

            root.findViewWithTag("font_phone").performClick();
            assertEquals("", Settings.preferences.getString(OwnFont.NAME_KEY, null));
            assertEquals("Back to your phone's font. Restart Messenger to see it.", ShadowToast.getTextOfLatestToast());
            assertTrue(status.getText().toString().startsWith("None chosen"));
        }
    }

    /** #48: a picked file can be sized and moved in steps, held to what the font file takes. */
    @Test public void aPickedFileIsSizedAndMovedInSteps() throws Exception {
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            assertEquals("nothing picked, nothing to size", View.GONE, root.findViewWithTag("font_adjust").getVisibility());
        }
        OwnFontTest.pick(FontFileTest.STATIC_FONT, "Rubik-Regular.ttf");
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            SettingsActivity activity = screen.get();
            View root = activity.getWindow().getDecorView();
            assertEquals(View.VISIBLE, root.findViewWithTag("font_adjust").getVisibility());
            TextView size = root.findViewWithTag("font_size_value"), place = root.findViewWithTag("font_shift_value");
            View smaller = root.findViewWithTag("font_size_smaller"), larger = root.findViewWithTag("font_size_larger");
            View up = root.findViewWithTag("font_shift_up"), down = root.findViewWithTag("font_shift_down");
            assertEquals("Text size 100%", size.getText().toString());
            assertEquals("Text sits where the font puts it", place.getText().toString());

            larger.performClick();
            assertEquals(105, Settings.preferences.getInt(OwnFont.SCALE_KEY, 0));
            assertEquals("Text size 105%", size.getText().toString());
            assertEquals("Text size 105%. Restart Messenger to see it.", ShadowToast.getTextOfLatestToast());
            for (int step = 0; step < 20; step++) larger.performClick();
            assertEquals(FontFile.SCALE_MOST, Settings.preferences.getInt(OwnFont.SCALE_KEY, 0));
            assertFalse(larger.isEnabled());
            for (int step = 0; step < 20; step++) smaller.performClick();
            assertEquals(FontFile.SCALE_LEAST, Settings.preferences.getInt(OwnFont.SCALE_KEY, 0));
            assertEquals("Text size 70%", size.getText().toString());
            assertFalse(smaller.isEnabled());
            assertTrue(larger.isEnabled());

            up.performClick();
            assertEquals(-2, Settings.preferences.getInt(OwnFont.SHIFT_KEY, 0));
            assertEquals("Text moved up 2%", place.getText().toString());
            down.performClick();
            down.performClick();
            assertEquals("Text moved down 2%", place.getText().toString());
            assertEquals("Text moved down 2%. Restart Messenger to see it.", ShadowToast.getTextOfLatestToast());
            for (int step = 0; step < 20; step++) down.performClick();
            assertEquals(FontFile.SHIFT_MOST, Settings.preferences.getInt(OwnFont.SHIFT_KEY, 0));
            assertFalse(down.isEnabled());
            assertTrue(up.isEnabled());

            // Going back to the phone's font takes the sized copies with the picked one, and the rows go.
            File sized = FontFile.adjusted(FontFile.file(activity), 120, 0);
            assertNotNull(sized);
            root.findViewWithTag("font_phone").performClick();
            assertFalse(sized.exists());
            assertEquals(View.GONE, root.findViewWithTag("font_adjust").getVisibility());
        }
    }

    @Test @Config(sdk = 36) public void fontAdjustmentValuesAndButtonsFitLargeTextInBothDirections() throws Exception {
        OwnFontTest.pick(FontFileTest.STATIC_FONT, "Rubik-Regular.ttf");
        for (String locale : new String[] {"en-rUS", "ar-rXB"}) {
            RuntimeEnvironment.setQualifiers(locale + "-w320dp-h640dp-mdpi");
            RuntimeEnvironment.setFontScale(2f);
            for (boolean light : new boolean[] {false, true}) {
                Settings.preferences.edit().putBoolean("light", light).commit();
                try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
                    View root = screen.get().getWindow().getDecorView();
                    ((EditText) root.findViewWithTag("find_control")).setText("Use your own font");
                    for (int pass = 0; pass < 2; pass++) {
                        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
                        root.layout(0, 0, 320, 640);
                        Shadows.shadowOf(Looper.getMainLooper()).idle();
                    }
                    for (String tag : new String[] {"font_size_value", "font_shift_value"}) {
                        TextView value = root.findViewWithTag(tag);
                        assertTrue(locale + " " + tag + " has room for its text", value.getWidth() > 0);
                        assertTrue(locale + " " + tag + " stays readable", value.getLineCount() >= 1 && value.getLineCount() <= 3);
                        assertEquals(0, value.getLayout().getEllipsisCount(value.getLineCount() - 1));
                        assertTrue(value.getLayout().getHeight() <= value.getHeight() - value.getTotalPaddingTop() - value.getTotalPaddingBottom());
                    }
                    for (String[] pair : new String[][] {{"font_size_smaller", "font_size_larger"}, {"font_shift_up", "font_shift_down"}}) {
                        View first = root.findViewWithTag(pair[0]), second = root.findViewWithTag(pair[1]);
                        View row = (View) first.getParent();
                        for (View button : new View[] {first, second}) {
                            assertTrue(locale + " " + button.getTag() + " has a usable width", button.getWidth() > 0);
                            assertTrue(button.getLeft() >= 0 && button.getRight() <= row.getWidth());
                        }
                        assertTrue("Step buttons share the available width", Math.abs(first.getWidth() - second.getWidth()) <= 1);
                    }
                    String screenshots = System.getenv("HUSH_SCREENSHOTS");
                    if (screenshots != null && !screenshots.isBlank()) {
                        Files.createDirectories(new File(screenshots).toPath());
                        for (String tag : new String[] {"font_size_value", "font_shift_value"}) {
                            View value = root.findViewWithTag(tag);
                            android.view.ViewGroup adjustment = (android.view.ViewGroup) value.getParent();
                            View actions = adjustment.getChildAt(adjustment.indexOfChild(value) + 1);
                            adjustment.requestRectangleOnScreen(new android.graphics.Rect(Math.min(value.getLeft(), actions.getLeft()),
                                value.getTop(), Math.max(value.getRight(), actions.getRight()), actions.getBottom()), true);
                            root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
                            root.layout(0, 0, 320, 640);
                            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(320, 640, android.graphics.Bitmap.Config.ARGB_8888);
                            try (var stream = new java.io.ByteArrayOutputStream()) {
                                root.draw(new android.graphics.Canvas(bitmap));
                                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream));
                                Files.write(new File(screenshots, "settings-" + tag + "-" + (light ? "light" : "dark") + "-" +
                                    (locale.equals("ar-rXB") ? "rtl" : "large") + ".png").toPath(), stream.toByteArray());
                            } finally { bitmap.recycle(); }
                        }
                    }
                }
            }
        }
    }

    @Test public void aNameWhoseCopyIsGoneSaysSoAndKeepsTheWayBack() {
        Settings.preferences.edit().putString(OwnFont.NAME_KEY, "Gone.ttf").commit();
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            assertEquals("HushMessenger's copy of Gone.ttf is gone, so your phone's font is used. Choose the file again.",
                ((TextView) root.findViewWithTag("font_file_status")).getText().toString());
            assertEquals(View.VISIBLE, root.findViewWithTag("font_phone").getVisibility());
        }
    }

    @Test public void aPickedNameIsOneShortLineWithNothingHidden() {
        assertEquals("your font file", SettingsActivity.cleanFontName(null));
        assertEquals("your font file", SettingsActivity.cleanFontName(" ‎\u0007 "));
        assertEquals("My Font.ttf", SettingsActivity.cleanFontName("My‮  Font\u0000.ttf"));
        assertEquals("a line break goes, it doesn't split the row", "MyFont.ttf", SettingsActivity.cleanFontName("My\nFont.ttf"));
        assertEquals(SettingsActivity.MAX_FONT_NAME, SettingsActivity.cleanFontName("x".repeat(500)).length());
        Uri uri = Uri.parse("content://fonts/document/Inter-Regular.otf");
        assertEquals("Inter-Regular.otf", SettingsActivity.fontName(RuntimeEnvironment.getApplication(), uri));
        // A storage document ID carries its folders, which never show.
        Uri document = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FFonts%2FInter-Regular.otf");
        assertEquals("primary:Download/Fonts/Inter-Regular.otf", document.getLastPathSegment());
        assertEquals("Inter-Regular.otf", SettingsActivity.fontName(RuntimeEnvironment.getApplication(), document));
        assertEquals("Inter-Regular.otf", SettingsActivity.lastPart("primary:Inter-Regular.otf"));
        assertEquals("your font file", SettingsActivity.cleanFontName(SettingsActivity.lastPart("primary:Download/")));
        assertNull(SettingsActivity.lastPart(null));
    }

    private static void awaitToast(String prefix) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            String toast = ShadowToast.getTextOfLatestToast();
            if (toast != null && toast.startsWith(prefix)) return;
            Thread.sleep(20);
        } while (System.currentTimeMillis() < deadline);
        fail("Missing font result: " + prefix + "; latest toast: " + ShadowToast.getTextOfLatestToast());
    }
}
