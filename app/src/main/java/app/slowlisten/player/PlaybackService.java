package app.slowlisten.player;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

@OptIn(markerClass = UnstableApi.class)
public final class PlaybackService extends MediaSessionService {
    private static final long SAVE_INTERVAL_MS = 5_000;
    static final String OPEN_PLAYER = "app.slowlisten.player.OPEN_PLAYER";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private MediaSession session;
    private RecordingStore store;

    private final Runnable checkpoint = new Runnable() {
        @Override
        public void run() {
            saveCurrentPosition();
            if (player.isPlaying()) {
                handler.postDelayed(this, SAVE_INTERVAL_MS);
            }
        }
    };

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            handler.removeCallbacks(checkpoint);
            saveCurrentPosition();
            if (isPlaying) {
                handler.postDelayed(checkpoint, SAVE_INTERVAL_MS);
            }
        }

        @Override
        public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                                            Player.PositionInfo newPosition, int reason) {
            if (oldPosition.mediaItem != null && (newPosition.mediaItem == null
                    || !oldPosition.mediaItem.mediaId.equals(newPosition.mediaItem.mediaId))) {
                long position = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                        ? 0 : oldPosition.positionMs;
                store.savePosition(oldPosition.mediaItem.mediaId, position);
            }
            saveCurrentPosition();
        }

        @Override
        public void onEvents(Player source, Player.Events events) {
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                saveCurrentPosition();
            }
            if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) {
                store.setSpeed(source.getPlaybackParameters().speed);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        store = RecordingStore.get(this);
        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build();
        player.setPlaybackSpeed(store.speed());
        restoreQueue();
        player.addListener(listener);

        Intent openPlayer = new Intent(this, MainActivity.class)
                .setAction(OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openApp = PendingIntent.getActivity(this, 0, openPlayer,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        session = new MediaSession.Builder(this, player)
                .setSessionActivity(openApp)
                .setCallback(new MediaSession.Callback() {
                    @Override
                    public ListenableFuture<List<MediaItem>> onAddMediaItems(MediaSession session,
                            MediaSession.ControllerInfo controller, List<MediaItem> requests) {
                        List<MediaItem> resolved = new ArrayList<>();
                        for (MediaItem request : requests) {
                            RecordingStore.Recording recording = store.find(request.mediaId);
                            if (recording == null) {
                                return Futures.immediateFailedFuture(
                                        new IllegalArgumentException("未知的录音"));
                            }
                            resolved.add(store.mediaItem(recording));
                        }
                        return Futures.immediateFuture(resolved);
                    }
                }).build();
    }

    private void restoreQueue() {
        List<RecordingStore.Recording> recordings = store.list();
        if (recordings.isEmpty()) {
            return;
        }
        String lastId = store.lastId();
        List<MediaItem> queue = new ArrayList<>();
        int index = 0;
        for (RecordingStore.Recording recording : recordings) {
            if (recording.id().equals(lastId)) {
                index = queue.size();
            }
            queue.add(store.mediaItem(recording));
        }
        player.setMediaItems(queue, index, recordings.get(index).position());
        player.prepare();
    }

    private void saveCurrentPosition() {
        MediaItem item = player.getCurrentMediaItem();
        if (item == null || player.getPlayerError() != null) {
            return;
        }
        long position = player.getPlaybackState() == Player.STATE_ENDED
                ? 0 : player.getCurrentPosition();
        store.setLastId(item.mediaId);
        store.savePosition(item.mediaId, position);
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(checkpoint);
        if (player != null) {
            saveCurrentPosition();
            player.removeListener(listener);
        }
        if (session != null) {
            session.release();
            session = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
