package com.simpleplayer.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Home screen: pick files, detect their type, and open the matching player. */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // "Open with Simple Player" from a file manager / other app.
        Intent in = getIntent();
        if (Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
            List<Uri> one = new ArrayList<>();
            one.add(in.getData());
            if (openMedia(one)) {
                finish();
                return;
            }
        }

        setContentView(R.layout.activity_main);
        findViewById(R.id.openButton).setOnClickListener(v -> pickFiles());
    }

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "audio/*", "video/*", "application/ogg", "application/x-ogg", "application/x-matroska"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;

        List<Uri> picked = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) picked.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            picked.add(data.getData());
        }
        if (!openMedia(picked)) {
            Toast.makeText(this, R.string.unsupported_file, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Detects the type of the first recognised file, keeps only files of that same type
     * (the two players are never mixed) and starts the matching player.
     */
    private boolean openMedia(List<Uri> uris) {
        int type = MediaUtils.TYPE_UNKNOWN;
        ArrayList<Uri> playlist = new ArrayList<>();
        for (Uri u : uris) {
            int t = MediaUtils.detectType(this, u);
            if (t == MediaUtils.TYPE_UNKNOWN) continue;
            if (type == MediaUtils.TYPE_UNKNOWN) type = t;
            if (t == type) playlist.add(u);
        }
        if (playlist.isEmpty()) return false;

        Intent play = new Intent(this, type == MediaUtils.TYPE_AUDIO
                ? MusicPlayerActivity.class : VideoPlayerActivity.class);
        play.putParcelableArrayListExtra(MediaUtils.EXTRA_URIS, playlist);

        ClipData clip = ClipData.newRawUri("media", playlist.get(0));
        for (int i = 1; i < playlist.size(); i++) clip.addItem(new ClipData.Item(playlist.get(i)));
        play.setClipData(clip);
        play.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(play);
        return true;
    }
}
