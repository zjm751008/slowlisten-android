package app.slowlisten.player;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.media.MediaExtractor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class RecordingStore extends SQLiteOpenHelper {
    static final int MAX_TEXT_LENGTH = 250_000;
    private static final String DEMO_ID = "morning-walk";
    private static final String TABLE = "recordings";
    private static RecordingStore instance;

    static synchronized RecordingStore get(Context context) {
        if (instance == null) {
            instance = new RecordingStore(context.getApplicationContext());
        }
        return instance;
    }

    private final Context context;
    private final File directory;
    private final SharedPreferences playback;

    private RecordingStore(Context context) {
        super(context, "recordings.db", null, 2);
        this.context = context;
        directory = new File(context.getFilesDir(), TABLE);
        playback = context.getSharedPreferences("playback", Context.MODE_PRIVATE);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE recordings (id TEXT PRIMARY KEY, title TEXT NOT NULL, "
                + "transcript TEXT NOT NULL, duration INTEGER NOT NULL, "
                + "position INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, alignment TEXT)");
        ContentValues demo = recordingValues(DEMO_ID, "A Morning Walk",
                "I try to take a short walk before I start work. Some mornings, I bring my phone. "
                        + "Other mornings, I leave it at home and listen to the sounds around me.\n\n"
                        + "There is a small bakery at the end of my street. When I pass it, "
                        + "the smell of fresh bread always makes me slow down. "
                        + "By the time I get back, I feel ready for the day.",
                20_484, 0);
        db.insertOrThrow(TABLE, null, demo);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE recordings ADD COLUMN alignment TEXT");
        }
    }

    List<Recording> list() {
        List<Recording> recordings = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE,
                new String[]{"id", "title", "duration", "position"}, null, null,
                null, null, "created DESC, rowid DESC")) {
            while (cursor.moveToNext()) {
                recordings.add(new Recording(cursor.getString(0), cursor.getString(1), "",
                        cursor.getLong(2), cursor.getLong(3), null));
            }
        }
        return recordings;
    }

    Recording find(String id) {
        try (Cursor cursor = getReadableDatabase().query(TABLE,
                new String[]{"id", "title", "transcript", "duration", "position", "alignment"},
                "id = ?", new String[]{id}, null, null, null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return new Recording(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getLong(3), cursor.getLong(4), cursor.getString(5));
        }
    }

    String displayName(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) {
                return cursor.getString(0);
            }
        } catch (RuntimeException ignored) {
            // Some document providers grant stream access without exposing a display name.
        }
        return "录音";
    }

    Recording add(Uri source, String title, String transcript) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建录音目录");
        }
        String id = UUID.randomUUID().toString();
        File temp = File.createTempFile("import-", ".tmp", directory);
        File target = audioFile(id);
        try {
            try (InputStream input = context.getContentResolver().openInputStream(source);
                 FileOutputStream output = new FileOutputStream(temp)) {
                if (input == null) {
                    throw new IOException("无法读取音频文件");
                }
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) != -1; ) {
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            long duration = audioDuration(temp);
            if (!temp.renameTo(target)) {
                throw new IOException("无法保存音频文件");
            }
            try {
                getWritableDatabase().insertOrThrow(TABLE, null,
                        recordingValues(id, title, transcript, duration, System.currentTimeMillis()));
            } catch (RuntimeException error) {
                target.delete();
                throw error;
            }
            return new Recording(id, title, transcript, duration, 0, null);
        } finally {
            temp.delete();
        }
    }

    private static long audioDuration(File file) throws IOException {
        try (MediaMetadataRetriever metadata = new MediaMetadataRetriever()) {
            metadata.setDataSource(file.getAbsolutePath());
            String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration == null
                    || !"yes".equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))) {
                throw new IOException("文件中没有可播放的音频");
            }
            return Long.parseLong(duration);
        } catch (RuntimeException error) {
            throw new IOException("无法读取此音频，请尝试 MP3、M4A 或 WAV 文件", error);
        }
    }

    private static ContentValues recordingValues(String id, String title, String transcript,
                                                 long duration, long created) {
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("title", title);
        values.put("transcript", transcript);
        values.put("duration", duration);
        values.put("created", created);
        return values;
    }

    void edit(String id, String title, String transcript) {
        getWritableDatabase().execSQL("UPDATE recordings SET title = ?, "
                        + "alignment = CASE WHEN transcript = ? THEN alignment ELSE NULL END, "
                        + "transcript = ? WHERE id = ?",
                new Object[]{title, transcript, transcript, id});
    }

    void saveAlignment(Recording recording, String alignment) {
        ContentValues values = new ContentValues();
        values.put("alignment", alignment);
        getWritableDatabase().update(TABLE, values, "id = ? AND transcript = ?",
                new String[]{recording.id(), recording.transcript()});
    }

    void openAudio(Recording recording, MediaExtractor extractor) throws IOException {
        if (!DEMO_ID.equals(recording.id())) {
            extractor.setDataSource(audioFile(recording.id()).getAbsolutePath());
            return;
        }
        try (AssetFileDescriptor audio = context.getAssets().openFd("morning-walk.mp3")) {
            extractor.setDataSource(audio.getFileDescriptor(), audio.getStartOffset(), audio.getLength());
        }
    }

    void delete(String id) {
        getWritableDatabase().delete(TABLE, "id = ?", new String[]{id});
        if (!DEMO_ID.equals(id)) {
            audioFile(id).delete();
        }
        if (id.equals(lastId())) {
            playback.edit().remove("lastId").apply();
        }
    }

    void savePosition(String id, long position) {
        ContentValues values = new ContentValues();
        values.put("position", Math.max(0, position));
        getWritableDatabase().update(TABLE, values, "id = ?", new String[]{id});
    }

    String lastId() {
        return playback.getString("lastId", "");
    }

    void setLastId(String id) {
        if (!id.equals(lastId())) {
            playback.edit().putString("lastId", id).apply();
        }
    }

    float speed() {
        return playback.getFloat("speed", 1f);
    }

    void setSpeed(float speed) {
        playback.edit().putFloat("speed", speed).apply();
    }

    MediaItem mediaItem(Recording recording) {
        Uri uri = DEMO_ID.equals(recording.id())
                ? Uri.parse("asset:///morning-walk.mp3") : Uri.fromFile(audioFile(recording.id()));
        return request(recording).buildUpon().setUri(uri).build();
    }

    static MediaItem request(Recording recording) {
        return new MediaItem.Builder()
                .setMediaId(recording.id())
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(recording.title())
                        .setArtist("慢听英语")
                        .setIsPlayable(true)
                        .build())
                .build();
    }

    private File audioFile(String id) {
        return new File(directory, id + ".audio");
    }

    record Recording(String id, String title, String transcript, long duration, long position,
                     String alignment) {}
}
