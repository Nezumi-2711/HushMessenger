package app.hushmessenger.extension;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * The pick screen the chat's gallery button starts with the switch on. It opens the phone's gallery app and hands what
 * the person picks back to the chat. Not exported, and it draws nothing: the gallery app is the only screen they see.
 */
public final class GalleryActivity extends Activity {
    private static final int PICK = 1;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Android stopped Messenger while the gallery app was open. The gallery's answer still comes back here.
        if (state != null) return;
        Intent pick = GalleryPicker.pickIntent(this);
        if (pick == null) {
            give("gallery_missing");
            return;
        }
        try {
            startActivityForResult(pick, PICK);
        } catch (ActivityNotFoundException error) {
            give("gallery_missing");
        } catch (RuntimeException error) {
            Settings.hookFailedPrivately(GalleryPicker.KEY, "Can't open your gallery app", error);
            give("gallery_failed");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK) return;
        Intent picked = null;
        try {
            if (resultCode == RESULT_OK) picked = GalleryPicker.handBack(this, data);
        } catch (RuntimeException error) {
            Settings.hookFailedPrivately(GalleryPicker.KEY, "Couldn't read what your gallery app picked", error);
        }
        if (picked != null) setResult(RESULT_OK, picked);
        else setResult(RESULT_CANCELED);
        finish();
    }

    /** Ends with nothing picked, so the chat stays as it was. */
    private void give(String message) {
        Toast.makeText(this, new SettingsText(this).get(message), Toast.LENGTH_LONG).show();
        setResult(RESULT_CANCELED);
        finish();
    }
}
