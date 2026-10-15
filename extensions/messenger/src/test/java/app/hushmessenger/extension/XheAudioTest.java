package app.hushmessenger.extension;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class XheAudioTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Before public void reset() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.preferences.edit().clear().commit();
        Settings.activeAt.clear();
        CrashGuard.resetForTests();
    }

    private static ByteBuffer config(int... bytes) {
        ByteBuffer buffer = ByteBuffer.allocate(bytes.length);
        for (int b : bytes) buffer.put((byte) b);
        buffer.flip();
        return buffer;
    }

    /** Runs the hook the way Messenger does, on its background copy thread. */
    private static void fromCopyThread(File file) throws InterruptedException {
        Thread copy = new Thread(() -> XheAudio.fixSharedFile(file));
        copy.start();
        copy.join(10_000);
        assertFalse(copy.isAlive());
    }

    @Test public void theObjectTypeIsReadFromTheAudioConfig() {
        assertEquals("AAC-LC", 2, XheAudio.objectType(config(0x12, 0x10)));
        assertEquals("HE-AAC", 5, XheAudio.objectType(config(0x2B, 0x92)));
        // 31 escapes to six more bits: 11111 001|010 is 32 + 10.
        assertEquals("xHE-AAC", XheAudio.XHE_OBJECT_TYPE, XheAudio.objectType(config(0xF9, 0x40)));
        assertEquals(-1, XheAudio.objectType(config(0xF9)));
        assertEquals(-1, XheAudio.objectType(config()));
        assertEquals(-1, XheAudio.objectType(null));
        ByteBuffer shared = config(0xF9, 0x40);
        XheAudio.objectType(shared);
        assertEquals("the format's own buffer isn't moved", 0, shared.position());
    }

    @Test public void aSoundTrackStartingBelowZeroIsKeptAndBothTracksMoveTogether() {
        // ffmpeg's AAC encoder delay: the sound's first sample sits at -23219 us, the picture's at 0.
        assertEquals(23_219L, XheAudio.startShift(true, 0L, true, -23_219L));
        assertEquals(23_219L, XheAudio.startShift(false, -1L, true, -23_219L));
        assertEquals(0L, XheAudio.startShift(true, 0L, true, 0L));
        assertEquals("a finished track's -1 isn't a start", 0L, XheAudio.startShift(true, 0L, false, -1L));
        assertFalse("the negative sound sample goes first", XheAudio.takePicture(true, 0L, true, -23_219L));
        assertFalse("the sound isn't over just because its time is negative", XheAudio.takePicture(false, -1L, true, -23_219L));
        assertTrue(XheAudio.takePicture(true, 0L, true, 0L));
        assertTrue(XheAudio.takePicture(true, 40_000L, false, -1L));
    }

    @Test public void aCopyThatCantBeReadStaysAsItWasAndLeavesNothingBehind() throws Exception {
        File copy = folder.newFile("orca_share_media123.mp4");
        byte[] bytes = "not a video".getBytes();
        Files.write(copy.toPath(), bytes);
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        fromCopyThread(copy);
        assertTrue("the switch was asked", Settings.lastActive(XheAudio.KEY) > 0);
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        assertArrayEquals(bytes, Files.readAllBytes(copy.toPath()));
        assertEquals(Arrays.asList("orca_share_media123.mp4"), Arrays.asList(folder.getRoot().list()));
    }

    @Test public void offPauseAndTheMainThreadLeaveTheCopyAlone() throws Exception {
        File copy = folder.newFile("orca_share_media7.mp4");
        byte[] bytes = "not a video".getBytes();
        Files.write(copy.toPath(), bytes);
        fromCopyThread(copy);
        assertEquals("off", 0, Settings.lastActive(XheAudio.KEY));
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).putBoolean("paused", true).commit();
        fromCopyThread(copy);
        assertEquals("paused", 0, Settings.lastActive(XheAudio.KEY));
        Settings.preferences.edit().putBoolean("paused", false).commit();
        // Robolectric runs the test on the main thread, where a conversion could freeze Messenger.
        XheAudio.fixSharedFile(copy);
        assertEquals("main thread", 0, Settings.lastActive(XheAudio.KEY));
        XheAudio.fixSharedFile(null);
        assertArrayEquals(bytes, Files.readAllBytes(copy.toPath()));
    }

    @Test public void onlyMp4CopiesAreConsidered() throws Exception {
        for (String name : new String[] {"orca_share_media1.mov", "orca_share_media2.3gp", "orca_share_media3.webm", "orca_share_media4"}) {
            File copy = folder.newFile(name);
            Files.write(copy.toPath(), "not a video".getBytes());
            assertFalse(name, XheAudio.fix(copy));
        }
    }

    /** Runs the transcoder hook the way Messenger does, off the main thread, and answers what it gave back. */
    private static String fromTranscoder(String source) throws InterruptedException {
        String[] answer = new String[1];
        Thread transcoder = new Thread(() -> answer[0] = XheAudio.transcodeSource(source));
        transcoder.start();
        transcoder.join(10_000);
        assertFalse(transcoder.isAlive());
        return answer[0];
    }

    @Test public void aPickedVideoThatDoesntNeedItComesBackAsItWas() throws Exception {
        File picked = folder.newFile("VID_20261014.mp4");
        byte[] bytes = "not a video".getBytes();
        Files.write(picked.toPath(), bytes);
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        for (String source : new String[] {picked.getPath(), "file://" + picked.getPath(),
            "content://media/external/video/media/1", "https://example.com/v.mp4", "not a path", ""}) {
            assertEquals(source, fromTranscoder(source));
        }
        assertNull(fromTranscoder(null));
        assertTrue("the switch was asked", Settings.lastActive(XheAudio.KEY) > 0);
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        assertArrayEquals("the picked file is never touched", bytes, Files.readAllBytes(picked.toPath()));
        File cache = new File(RuntimeEnvironment.getApplication().getCacheDir(), XheAudio.PICKED);
        assertFalse("nothing was converted, so no cache folder", cache.exists());
    }

    @Test public void offPauseAndTheMainThreadGiveTheSourceBack() throws Exception {
        File picked = folder.newFile("VID_1.mp4");
        assertEquals(picked.getPath(), fromTranscoder(picked.getPath()));
        assertEquals("off", 0, Settings.lastActive(XheAudio.KEY));
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).putBoolean("paused", true).commit();
        assertEquals(picked.getPath(), fromTranscoder(picked.getPath()));
        assertEquals("paused", 0, Settings.lastActive(XheAudio.KEY));
        Settings.preferences.edit().putBoolean("paused", false).commit();
        assertEquals(picked.getPath(), XheAudio.transcodeSource(picked.getPath()));
        assertEquals("main thread", 0, Settings.lastActive(XheAudio.KEY));
    }

    /** One box: a 32-bit size, the type and [body]. */
    private static byte[] box(String type, byte[] body) {
        ByteBuffer box = ByteBuffer.allocate(8 + body.length);
        box.putInt(8 + body.length).put(type.getBytes(StandardCharsets.US_ASCII)).put(body);
        return box.array();
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static final byte[] FILE_TYPE = box("ftyp", "isom\0\0\2\0isommp42".getBytes(StandardCharsets.US_ASCII));

    /** Shaped like the #58 recording: ftyp, the picture data, moov with Samsung's udta records, then sefd at the end. */
    private static byte[] samsungRecording() {
        return join(FILE_TYPE, box("mdat", new byte[64]),
            box("moov", join(box("mvhd", new byte[100]), box("udta", box("smta", new byte[12])))),
            box("sefd", "\0\0\u0080\0Samsung_Capture_Info\0Screen recording".getBytes(StandardCharsets.ISO_8859_1)));
    }

    /** [bytes] with the box type at [offset] swapped for free. */
    private static byte[] renamedAt(byte[] bytes, int offset) {
        byte[] renamed = bytes.clone();
        System.arraycopy("free".getBytes(StandardCharsets.US_ASCII), 0, renamed, offset + 4, 4);
        return renamed;
    }

    @Test public void aSamsungRecordingsShareCopyHasItsTagRenamedInPlace() throws Exception {
        File copy = folder.newFile("orca_share_media42.mp4");
        byte[] bytes = samsungRecording();
        Files.write(copy.toPath(), bytes);
        int sefd = bytes.length - box("sefd", "\0\0\u0080\0Samsung_Capture_Info\0Screen recording".getBytes(StandardCharsets.ISO_8859_1)).length;
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        fromCopyThread(copy);
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        assertArrayEquals("only the top-level sefd's type changes, so every offset stays", renamedAt(bytes, sefd), Files.readAllBytes(copy.toPath()));
        assertEquals(Arrays.asList("orca_share_media42.mp4"), Arrays.asList(folder.getRoot().list()));
        assertFalse("nothing is left to rename", XheAudio.dropSamsungBoxes(copy));
    }

    @Test public void onlyTopLevelSamsungBoxesInAnMp4AreRenamed() throws Exception {
        byte[] nested = join(FILE_TYPE, box("moov", box("udta", box("sefd", new byte[8]))), box("mdat", new byte[16]));
        byte[] notMp4 = join(box("mdat", new byte[16]), box("sefd", new byte[8]));
        // A 64-bit sized mdat before the tag, as long recordings write it.
        ByteBuffer large = ByteBuffer.allocate(16 + 32);
        large.putInt(1).put("mdat".getBytes(StandardCharsets.US_ASCII)).putLong(16 + 32);
        byte[] wide = join(FILE_TYPE, large.array(), box("sefd", new byte[8]));
        // A box that claims more than the file holds ends the walk, so the sefd after it is never reached.
        ByteBuffer broken = ByteBuffer.allocate(8);
        broken.putInt(1 << 20).put("mdat".getBytes(StandardCharsets.US_ASCII));
        byte[] cut = join(FILE_TYPE, broken.array(), box("sefd", new byte[8]));
        byte[] tiny = join(FILE_TYPE, new byte[] {0, 0, 0, 4, 's', 'e', 'f', 'd'});
        for (byte[] bytes : new byte[][] {nested, notMp4, cut, tiny}) {
            File file = folder.newFile();
            Files.write(file.toPath(), bytes);
            assertFalse(XheAudio.hasSamsungBox(file));
            assertFalse(XheAudio.dropSamsungBoxes(file));
            assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
        }
        File file = folder.newFile();
        Files.write(file.toPath(), wide);
        assertTrue(XheAudio.dropSamsungBoxes(file));
        assertArrayEquals(renamedAt(wide, FILE_TYPE.length + 48), Files.readAllBytes(file.toPath()));
        assertFalse("a missing file has none", XheAudio.hasSamsungBox(new File(folder.getRoot(), "gone.mp4")));
    }

    @Test public void aPickedSamsungRecordingIsSentAsARenamedCopy() throws Exception {
        File picked = folder.newFile("Screen_Recording_20261014.mp4");
        byte[] bytes = samsungRecording();
        Files.write(picked.toPath(), bytes);
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        // A path that starts with a slash, as Messenger passes it on the phone (here /C:/... on Windows).
        String path = picked.toURI().getPath();
        String sent = fromTranscoder(path);
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        File cache = new File(RuntimeEnvironment.getApplication().getCacheDir(), XheAudio.PICKED);
        assertEquals(cache.getCanonicalPath(), new File(sent).getParentFile().getCanonicalPath());
        assertArrayEquals(renamedAt(bytes, bytes.length - 8 - 41), Files.readAllBytes(new File(sent).toPath()));
        assertArrayEquals("the picked file is never touched", bytes, Files.readAllBytes(picked.toPath()));
        assertEquals("the same video reuses its copy", sent, fromTranscoder(path));
        assertEquals("only the finished copy is kept", 1, cache.list().length);
        assertTrue("a file Uri comes back as one", fromTranscoder("file://" + path).startsWith("file:"));
    }

    @Test public void convertedCopiesOlderThanADayAreCleared() throws Exception {
        File cache = folder.newFolder(XheAudio.PICKED);
        long now = System.currentTimeMillis();
        File stale = new File(cache, "1a2b.mp4");
        File fresh = new File(cache, "3c4d.mp4");
        Files.write(stale.toPath(), new byte[] {1});
        Files.write(fresh.toPath(), new byte[] {1});
        assertTrue(stale.setLastModified(now - XheAudio.KEEP_MS - 60_000));
        assertTrue(fresh.setLastModified(now - 60_000));
        XheAudio.prune(cache, now);
        assertEquals(Arrays.asList("3c4d.mp4"), Arrays.asList(cache.list()));
    }

    @Test public void theSwitchSitsUnderConversationsAndStartsOff() {
        assertTrue(Settings.installed.contains(XheAudio.KEY));
        String[] row = Arrays.stream(SettingsActivity.CONTROLS).filter(c -> c[0].equals(XheAudio.KEY)).findFirst().orElseThrow();
        assertEquals("Fix videos that won't send", row[1]);
        assertEquals("conversations", row[3]);
        assertFalse(Settings.preferences.contains(XheAudio.KEY));
        assertFalse(Settings.wouldUse(XheAudio.KEY));
    }
}
