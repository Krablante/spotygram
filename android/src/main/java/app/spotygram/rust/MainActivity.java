package app.spotygram.rust;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import dev.makepad.android.MakepadActivity;

public final class MainActivity extends MakepadActivity {
    private static final int IMPORT_AUDIO = 319;
    private static final String SHARE_HANDLED = "app.spotygram.rust.shareHandled";
    private static final java.util.concurrent.ExecutorService imports = Executors.newSingleThreadExecutor();
    @Override public void openAllMidiDevices(long delay) { /* Playback is owned by Media3. */ }
    @Override public void onCreate(Bundle state) { PlatformBridge.activity = new java.lang.ref.WeakReference<>(this); super.onCreate(state); if(state==null || !state.getBoolean(SHARE_HANDLED,false)) acceptShared(getIntent()); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putBoolean(SHARE_HANDLED,getIntent()!=null && getIntent().getBooleanExtra(SHARE_HANDLED,false)); super.onSaveInstanceState(state); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); acceptShared(intent); }
    @Override public void onStart() { super.onStart(); PlatformBridge.visible = true; PlatformBridge.command("visible", 1); }
    @Override public void onStop() { PlatformBridge.visible = false; PlatformBridge.command("visible", 0); super.onStop(); }

    void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "application/ogg", "application/x-ogg"})
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, IMPORT_AUDIO);
    }
    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != IMPORT_AUDIO || result != RESULT_OK || data == null) return;
        ArrayList<Uri> selected = new ArrayList<>();
        if (data.getClipData() != null) for (int i=0; i<data.getClipData().getItemCount(); i++) selected.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) selected.add(data.getData());
        importUris(selected);
    }
    @SuppressWarnings("deprecation")
    private void acceptShared(Intent intent) {
        if(intent==null || intent.getBooleanExtra(SHARE_HANDLED,false)) return;
        if(!Intent.ACTION_SEND.equals(intent.getAction()) && !Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) return;
        ArrayList<Uri> selected=new ArrayList<>();
        if(Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri uri=intent.getParcelableExtra(Intent.EXTRA_STREAM); if(uri!=null) selected.add(uri);
        } else if(Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> uris=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if(uris!=null) selected.addAll(uris);
        }
        if(selected.isEmpty() && intent.getClipData()!=null) {
            for(int i=0;i<intent.getClipData().getItemCount();i++) {
                Uri uri=intent.getClipData().getItemAt(i).getUri(); if(uri!=null) selected.add(uri);
            }
        }
        if(selected.isEmpty() && intent.getData()!=null) selected.add(intent.getData());
        if(!selected.isEmpty()) { intent.putExtra(SHARE_HANDLED,true); importUris(selected); }
        else android.util.Log.w("spotygram","Shared audio arrived without a readable URI");
    }
    private void importUris(ArrayList<Uri> selected) {
        imports.execute(() -> {
            JSONArray paths = new JSONArray();
            ArrayList<File> staged = new ArrayList<>();
            try {
                for (Uri uri : selected) {
                    String name = "audio";
                    try (var cursor = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                    }
                    name = new File(name==null ? "audio" : name).getName().replaceAll("[\\p{Cntrl}/\\\\]", "_");
                    if(name.trim().isEmpty() || name.equals(".") || name.equals("..")) name="audio";
                    File directory = new File(getFilesDir(), "cache/import-" + UUID.randomUUID()); if (!directory.mkdirs()) throw new java.io.IOException("Cannot prepare audio import");
                    File file = new File(directory, name); staged.add(file);
                    try (var input = getContentResolver().openInputStream(uri); var output = new FileOutputStream(file)) {
                        if (input == null) throw new java.io.IOException("Cannot open selected audio");
                        byte[] buffer = new byte[65536]; int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    }
                    paths.put(file.getAbsolutePath());
                }
                if (!PlatformBridge.imported(paths.toString())) throw new java.io.IOException("The application is busy; select the files again shortly");
            } catch (Exception error) {
                android.util.Log.e("spotygram","Audio import failed",error);
                for (File file : staged) { file.delete(); file.getParentFile().delete(); }
                PlatformBridge.main.post(() -> android.widget.Toast.makeText(this, "Import failed: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }
}
