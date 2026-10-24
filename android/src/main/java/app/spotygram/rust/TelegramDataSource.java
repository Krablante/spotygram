package app.spotygram.rust;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import java.io.IOException;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class TelegramDataSource extends BaseDataSource {
    private long reader;
    private Uri uri;
    private boolean started;
    TelegramDataSource() { super(false); }
    @Override public long open(DataSpec spec) throws IOException {
        transferInitializing(spec);
        uri = spec.uri;
        reader = PlatformBridge.openAudio(uri.toString(), spec.position);
        if (reader == 0) throw new IOException("The audio stream could not be opened");
        long size = PlatformBridge.audioSize(reader);
        started = true; transferStarted(spec);
        return spec.length != C.LENGTH_UNSET ? spec.length : size > 0 ? Math.max(0, size - spec.position) : C.LENGTH_UNSET;
    }
    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        int count = PlatformBridge.readAudio(reader, buffer, offset, length);
        if (count < -1) throw new IOException("The Telegram audio stream failed");
        if (count > 0) bytesTransferred(count);
        return count;
    }
    @Override public Uri getUri() { return uri; }
    @Override public void close() {
        if (reader != 0) { PlatformBridge.closeAudio(reader); reader = 0; }
        if (started) { started = false; transferEnded(); }
        uri = null;
    }
}
