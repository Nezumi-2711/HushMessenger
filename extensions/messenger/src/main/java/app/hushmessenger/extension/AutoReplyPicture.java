package app.hushmessenger.extension;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * The picture an auto reply sends after its text. The picker's choice is copied into Messenger's own files as a JPEG
 * no bigger than MAX_EDGE on its long side, so a send never depends on the picked file staying where it was. It goes
 * out through Messenger's media message factory and the notification reply service's send, which the auto reply patch
 * fills in. A build where those didn't match sends the text alone.
 */
final class AutoReplyPicture {
    private AutoReplyPicture() {}

    static final String DIRECTORY = "hush-auto-reply";
    private static final Pattern NAME = Pattern.compile("PIC_\\d{1,19}\\.jpg");
    private static final String PARTIAL = ".part";
    static final int MAX_EDGE = 2048;
    static final int QUALITY = 90;
    /** How long Messenger gets to build the photo message, then to take it for sending. */
    static final long BUILD_SECONDS = 20;
    static final long SEND_SECONDS = 10;
    /** A copy left half written by a crash is cleared once it's this old. */
    static final long STALE_PARTIAL_MS = 10 * 60_000L;

    /**
     * Sends one picture to a chat while [wanted] still says yes. Returns null once Messenger took it, or why it didn't
     * go. Tests swap it.
     */
    interface Sender {
        String send(Context context, String thread, Uri picture, BooleanSupplier wanted);
    }

    /** Messenger's photo message factory and send, which the patch fills in. Tests swap them. */
    interface Messenger {
        Object build(Context context, String thread, Uri picture);
        Object send(Object message);
    }

    static volatile Sender sender = AutoReplyPicture::throughMessenger;
    static volatile Messenger messenger = new Messenger() {
        @Override public Object build(Context context, String thread, Uri picture) {
            return Settings.autoReplyPictureMessages(context, thread, picture);
        }

        @Override public Object send(Object message) {
            return Settings.autoReplySendMessage(message);
        }
    };
    /** Whether the patch filled in the media send on this build. Tests swap it. */
    static volatile BooleanSupplier bridge = Settings::autoReplyPictures;

    /** The settings show the picture choice only where it can be sent, and in the preview build for screenshots. */
    static boolean supported() {
        return Settings.preview || bridge.getAsBoolean();
    }

    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    static File directory(Context context) {
        return new File(context.getFilesDir(), DIRECTORY);
    }

    /** The file for a name this class wrote, or null for any other name, so a saved value can't point elsewhere. */
    static File fileFor(Context context, String name) {
        return validName(name) ? new File(directory(context), name) : null;
    }

    /**
     * Sends the picture [name] to the chat [thread] (a thread key string) while [wanted] still says yes. Returns null
     * once it went, or why not.
     */
    static String send(Context context, String thread, String name, BooleanSupplier wanted) {
        File file = fileFor(context, name);
        if (file == null) return "the picture's name isn't one this app wrote";
        if (!file.isFile()) return "the picture file is gone";
        if (!bridge.getAsBoolean()) return "this Messenger's media send didn't match";
        try {
            return sender.send(context, thread, Uri.fromFile(file), wanted);
        } catch (Throwable failure) {
            // The class only: a message can carry a path or a chat.
            return "sending failed with " + failure.getClass().getName();
        }
    }

    /** Builds the photo message with Messenger's factory and sends each one the factory returns. */
    static String throughMessenger(Context context, String thread, Uri picture, BooleanSupplier wanted) {
        Object built = messenger.build(context, thread, picture);
        if (!(built instanceof Future)) return "Messenger didn't build the picture message";
        Object messages;
        try {
            messages = ((Future<?>) built).get(BUILD_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException slow) {
            return "building the picture message took too long";
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return "stopped while building the picture message";
        } catch (ExecutionException failed) {
            return "building the picture message failed with " + className(failed.getCause());
        }
        if (!(messages instanceof Iterable)) return "Messenger built no picture message";
        int sent = 0;
        for (Object message : (Iterable<?>) messages) {
            if (message == null) continue;
            // Building can take seconds, so Off, Pause or edited replies in the meantime still stop the picture.
            if (!wanted.getAsBoolean()) return "the auto reply changed or went off before the picture went";
            Object sending = messenger.send(message);
            if (sending == null) return "Messenger didn't take the picture message";
            sent++;
            if (!(sending instanceof Future)) continue;
            try {
                ((Future<?>) sending).get(SEND_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException slow) {
                android.util.Log.w("HushMessenger", "auto_reply: the picture is still sending");
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return "stopped while sending the picture";
            } catch (ExecutionException failed) {
                return "sending the picture failed with " + className(failed.getCause());
            }
        }
        return sent == 0 ? "Messenger built no picture message" : null;
    }

    private static String className(Throwable failure) {
        return failure == null ? "nothing" : failure.getClass().getName();
    }

    /**
     * Copies the picked picture in as a JPEG, turned the way its EXIF says and no bigger than MAX_EDGE on its long
     * side. Returns the new file's name. It reads the picker's file three times (size, pixels, EXIF), so it runs off the
     * main thread.
     */
    static String copy(Context context, Uri uri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = open(resolver, uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("not a picture");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample(bounds.outWidth, bounds.outHeight, MAX_EDGE);
        Bitmap decoded;
        try (InputStream input = open(resolver, uri)) {
            decoded = BitmapFactory.decodeStream(input, null, options);
        }
        if (decoded == null) throw new IOException("not a picture");
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream input = open(resolver, uri)) {
            orientation = new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException noExif) {
            // A picture without readable EXIF is sent as it decodes.
        }
        Bitmap upright = upright(decoded, orientation);
        File folder = directory(context);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("can't make the picture folder");
        File partial = File.createTempFile("PIC_", PARTIAL, folder);
        try {
            try (FileOutputStream output = new FileOutputStream(partial)) {
                if (!upright.compress(Bitmap.CompressFormat.JPEG, QUALITY, output)) throw new IOException("can't write the picture");
                output.getFD().sync();
            }
            File target = freshName(folder);
            if (!partial.renameTo(target)) throw new IOException("can't save the picture");
            return target.getName();
        } finally {
            if (partial.exists() && !partial.delete()) android.util.Log.w("HushMessenger", "auto_reply: a half-copied picture stayed behind");
            if (upright != decoded) upright.recycle();
            decoded.recycle();
        }
    }

    private static InputStream open(ContentResolver resolver, Uri uri) throws IOException {
        InputStream input = resolver.openInputStream(uri);
        if (input == null) throw new IOException("the picture can't be opened");
        return input;
    }

    /** The largest power of two that still leaves the long side at least [edge], so the decode never goes under it. */
    static int sample(int width, int height, int edge) {
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / (sample * 2) >= edge) sample *= 2;
        return sample;
    }

    /** Shrinks [source] to MAX_EDGE and applies an EXIF orientation. Returns [source] itself when neither is needed. */
    static Bitmap upright(Bitmap source, int orientation) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        float scale = longest > MAX_EDGE ? MAX_EDGE / (float) longest : 1f;
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.postScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.postRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.postRotate(270); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
            default: if (scale == 1f) return source;
        }
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private static File freshName(File folder) {
        long stamp = System.currentTimeMillis();
        File target;
        while ((target = new File(folder, "PIC_" + stamp + ".jpg")).exists()) stamp++;
        return target;
    }

    /** A small copy of a saved picture for the settings, or null when it's gone or unreadable. */
    static Bitmap thumbnail(Context context, String name, int edge) {
        File file = fileFor(context, name);
        if (file == null || !file.isFile()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        decode(file, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample(bounds.outWidth, bounds.outHeight, Math.max(1, edge));
        return decode(file, options);
    }

    /** Decodes through a stream closed here, so a shown picture never stays open and can always be deleted. */
    private static Bitmap decode(File file, BitmapFactory.Options options) {
        try (InputStream input = new java.io.FileInputStream(file)) {
            return BitmapFactory.decodeStream(input, null, options);
        } catch (IOException gone) {
            return null;
        }
    }

    /**
     * Deletes saved pictures no reply uses any more, and half-written copies a crash left. Only names this class
     * writes are touched.
     */
    static void prune(Context context, Collection<String> keep) {
        File[] files = directory(context).listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            String name = file.getName();
            boolean unused = validName(name) && !keep.contains(name);
            boolean stale = name.startsWith("PIC_") && name.endsWith(PARTIAL) && now - file.lastModified() > STALE_PARTIAL_MS;
            if ((unused || stale) && !file.delete()) android.util.Log.w("HushMessenger", "auto_reply: an unused picture couldn't be deleted");
        }
    }
}
