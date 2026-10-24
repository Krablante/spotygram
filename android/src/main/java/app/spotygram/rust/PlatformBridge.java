package app.spotygram.rust;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Android facilities only. Catalog, Telegram, sync and playback policy belong to Rust. */
public final class PlatformBridge extends Application {
    static PlatformBridge app;
    static final Handler main = new Handler(Looper.getMainLooper());
    static final ArrayDeque<JSONObject> pending = new ArrayDeque<>();
    static PlaybackService playback;
    static java.lang.ref.WeakReference<MainActivity> activity = new java.lang.ref.WeakReference<>(null);
    static volatile boolean visible = true;

    static native void initialize();
    static native boolean command(String action, double value);
    static native void playerEvent(long generation, int event, double value, String error);
    static native long openAudio(String uri, long position);
    static native int readAudio(long reader, byte[] buffer, int offset, int length);
    static native long audioSize(long reader);
    static native void closeAudio(long reader);
    static native boolean imported(String paths);

    @Override public void onCreate() {
        super.onCreate();
        app = this;
        System.loadLibrary("makepad");
        initialize();
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { command("network", 0); }
            @Override public void onLost(Network network) { command("network", 0); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { command("network", 0); }
        });
    }

    public static String call(String method, String value) {
        try {
            return switch (method) {
                case "filesDirectory" -> app.getFilesDir().getAbsolutePath();
                case "databaseKey" -> databaseKey();
                case "wifi" -> Boolean.toString(wifi());
                case "language" -> java.util.Locale.getDefault().getLanguage();
                case "player" -> { JSONObject action = new JSONObject(value); main.post(() -> player(action)); yield ""; }
                case "pickFiles" -> { main.post(() -> { MainActivity owner = activity.get(); if (owner != null) owner.pickFiles(); }); yield ""; }
                case "openUrl" -> { app.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(value)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); yield ""; }
                case "visible" -> { visible = Boolean.parseBoolean(value); yield ""; }
                case "transfer" -> { main.post(() -> { try { if (Boolean.parseBoolean(value)) app.startForegroundService(new Intent(app, TransferService.class)); else app.stopService(new Intent(app, TransferService.class)); } catch (RuntimeException error) { android.util.Log.e("spotygram", "Cannot keep transfer in foreground", error); } }); yield ""; }
                case "shutdown" -> { main.post(() -> { pending.clear(); app.stopService(new Intent(app, PlaybackService.class)); app.stopService(new Intent(app, TransferService.class)); }); yield ""; }
                case "checkUpdate" -> checkUpdate(value);
                default -> throw new IllegalArgumentException("Unknown Android facility");
            };
        } catch (Exception error) { return "!error:" + error.getMessage(); }
    }

    static String checkUpdate(String current) throws Exception {
        var connection = (javax.net.ssl.HttpsURLConnection)new java.net.URL("https://api.github.com/repos/Krablante/spotygram/releases?per_page=30").openConnection();
        connection.setConnectTimeout(8000); connection.setReadTimeout(8000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "spotygram-rust"); connection.setRequestProperty("Accept", "application/vnd.github+json");
        try (var input = connection.getInputStream(); var output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count=input.read(buffer))!=-1) { if (output.size()+count>1048576) throw new java.io.IOException("Release response is too large"); output.write(buffer,0,count); }
            var releases = new org.json.JSONArray(new String(output.toByteArray(),StandardCharsets.UTF_8));
            long newest=version(current); String url="";
            for (int i=0;i<releases.length();i++) { var release=releases.getJSONObject(i); String tag=release.optString("tag_name");
                if (!release.optBoolean("prerelease") || release.optBoolean("draft") || !tag.startsWith("rust-v")) continue;
                long candidate=version(tag.substring(6)); if (candidate>newest) { newest=candidate; url=release.optString("html_url"); }
            }
            return url;
        } finally { connection.disconnect(); }
    }
    private static long version(String value) {
        try { String[] parts=value.split("\\."); if (parts.length!=3) return -1; long result=0;
            for (String part:parts) { int number=Integer.parseInt(part); if (number<0 || number>999999) return -1; result=result*1000000+number; } return result;
        } catch (NumberFormatException error) { return -1; }
    }

    static boolean wifi() {
        ConnectivityManager manager = app.getSystemService(ConnectivityManager.class);
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    static void player(JSONObject action) {
        try {
            if (playback != null) { playback.apply(action); return; }
            if (!action.optString("action").equals("load")) return;
            pending.addLast(action);
            app.startForegroundService(new Intent(app, PlaybackService.class));
        } catch (Exception error) { playerEvent(action.optLong("generation"), 6, 0, error.getMessage()); }
    }

    static String databaseKey() throws Exception {
        var prefs = app.getSharedPreferences("telegram-key", Context.MODE_PRIVATE);
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        String alias = "spotygram-rust-telegram";
        String wrapped = prefs.getString("wrapped", null);
        File directory = new File(app.getFilesDir(), "telegram/db");
        boolean existing = directory.isDirectory() && directory.list() != null && directory.list().length > 0;
        if (wrapped != null) {
            if (!store.containsAlias(alias)) throw new IllegalStateException("The original Telegram key is unavailable; existing data has been preserved");
            byte[] bytes = Base64.decode(wrapped, Base64.NO_WRAP);
            if (bytes.length < 29) throw new IllegalStateException("The saved Telegram key is invalid");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, (SecretKey) store.getKey(alias, null), new GCMParameterSpec(128, bytes, 0, 12));
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
        }
        if (existing) throw new IllegalStateException("The key for the existing Telegram database is missing; its data has been preserved");
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        String key = Base64.encodeToString(random, Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, (SecretKey) store.getKey(alias, null));
        byte[] encrypted = cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
        byte[] bytes = new byte[cipher.getIV().length + encrypted.length];
        System.arraycopy(cipher.getIV(), 0, bytes, 0, cipher.getIV().length);
        System.arraycopy(encrypted, 0, bytes, cipher.getIV().length, encrypted.length);
        if (!prefs.edit().putString("wrapped", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) throw new IllegalStateException("Cannot save the Telegram key");
        return key;
    }
}
