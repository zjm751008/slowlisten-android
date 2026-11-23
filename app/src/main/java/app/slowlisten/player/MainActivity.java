package app.slowlisten.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
public final class MainActivity extends Activity {
    static final int BACKGROUND = 0xff111a16;
    static final int SURFACE = 0xff1b2821;
    static final int BORDER = 0xff304139;
    static final int TEXT = 0xfff3f5ec;
    static final int MUTED = 0xffaab8ab;
    static final int ACCENT = 0xffdaef9d;
    private static final int AUDIO_PICKER = 20;
    private static final int TEXT_PICKER = 21;
    private static final float[] SPEEDS = {.65f, .75f, 1f, 1.25f, 1.5f, 2f};
    private static final ExecutorService IMPORTER = Executors.newSingleThreadExecutor();

    private enum Screen { LIBRARY, PLAYER, IMPORT }
    private Screen screen = Screen.LIBRARY;
    private Screen returnScreen = Screen.LIBRARY;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updateClock = new Runnable() {
        @Override
        public void run() {
            updateControls();
            handler.postDelayed(this, 350);
        }
    };

    private RecordingStore store;
    private ListenableFuture<MediaController> connection;
    private MediaController controller;
    private LinearLayout root;
    private AudioView playButton;
    private AudioView timelineArt;
    private SeekBar seekBar;
    private TextView elapsed;
    private TextView total;
    private TextView speedButton;
    private TextView playStatus;
    private TextView transcriptView;
    private TextView transcriptToggle;
    private boolean scrubbing;
    private boolean transcriptVisible = true;

    private String editId;
    private Uri draftAudio;
    private String draftFilename = "";
    private String draftTitle = "";
    private String draftTranscript = "";
    private boolean busy;
    private EditText titleInput;
    private EditText transcriptInput;
    private TextView fileButton;
    private TextView saveButton;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onEvents(Player player, Player.Events events) {
            if (screen != Screen.IMPORT && events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_MEDIA_METADATA_CHANGED)) {
                renderScreen();
            } else {
                updateControls();
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            toast("无法播放这段录音，请检查音频格式。");
        }
    };

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        store = RecordingStore.get(this);
        if (savedState != null) {
            screen = Screen.valueOf(savedState.getString("screen", Screen.LIBRARY.name()));
            returnScreen = Screen.valueOf(savedState.getString("returnScreen", Screen.LIBRARY.name()));
            transcriptVisible = savedState.getBoolean("transcriptVisible", true);
            editId = savedState.getString("editId");
            draftFilename = savedState.getString("filename", "");
            draftTitle = savedState.getString("title", "");
            draftTranscript = savedState.getString("transcript", "");
            String uri = savedState.getString("audio");
            draftAudio = uri == null ? null : Uri.parse(uri);
        } else if (PlaybackService.OPEN_PLAYER.equals(getIntent().getAction())) {
            screen = Screen.PLAYER;
        }
        getWindow().setDecorFitsSystemWindows(false);
        root = column();
        root.setBackgroundColor(BACKGROUND);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            var keyboard = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
        renderScreen();
        connect();
    }

    private void connect() {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        connection = new MediaController.Builder(this, token).buildAsync();
        connection.addListener(() -> {
            if (isDestroyed()) {
                return;
            }
            try {
                controller = connection.get();
                controller.addListener(playerListener);
                if (screen != Screen.IMPORT) {
                    renderScreen();
                }
            } catch (Exception error) {
                toast("播放器连接失败，请重新打开应用。");
            }
        }, getMainExecutor());
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(updateClock);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(updateClock);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (controller != null) {
            controller.removeListener(playerListener);
        }
        if (connection != null) {
            MediaController.releaseFuture(connection);
        }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (PlaybackService.OPEN_PLAYER.equals(intent.getAction()) && screen != Screen.IMPORT) {
            show(Screen.PLAYER);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        captureDraft();
        outState.putString("screen", screen.name());
        outState.putString("returnScreen", returnScreen.name());
        outState.putBoolean("transcriptVisible", transcriptVisible);
        outState.putString("editId", editId);
        outState.putString("filename", draftFilename);
        outState.putString("title", draftTitle);
        outState.putString("transcript", draftTranscript);
        if (draftAudio != null) {
            outState.putString("audio", draftAudio.toString());
        }
    }

    @Override
    public void onBackPressed() {
        if (screen == Screen.PLAYER) {
            show(Screen.LIBRARY);
        } else if (screen == Screen.IMPORT) {
            closeImport();
        } else {
            super.onBackPressed();
        }
    }

    private void show(Screen destination) {
        screen = destination;
        renderScreen();
    }

    private void renderScreen() {
        root.removeAllViews();
        playButton = null;
        timelineArt = null;
        seekBar = null;
        speedButton = null;
        playStatus = null;
        scrubbing = false;
        switch (screen) {
            case LIBRARY -> renderLibrary();
            case PLAYER -> renderPlayer();
            case IMPORT -> renderImport();
        }
        updateControls();
    }

    private void renderLibrary() {
        LinearLayout header = toolbar();
        LinearLayout brand = row();
        brand.addView(new AudioView(this, "mark", ACCENT), new LinearLayout.LayoutParams(dp(30), dp(34)));
        TextView name = text("慢听英语", 19, TEXT, true);
        addSpaced(brand, name, 8);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(48), 1));
        header.addView(pill("＋ 导入", true, () -> openImport(null)));

        LinearLayout body = scrollingBody();
        add(body, text("听力库", 34, TEXT, true), 15);
        List<RecordingStore.Recording> recordings = store.list();
        if (recordings.isEmpty()) {
            LinearLayout empty = panel(SURFACE, 24, 22);
            add(body, empty, 20);
            add(empty, text("导入第一段录音", 20, TEXT, true), 0);
            add(empty, text("从手机选择音频，再粘贴或导入原文。", 14, MUTED, false), 8);
            add(empty, pill("选择音频", true, () -> openImport(null)), 20);
            return;
        }

        String currentId = currentId();
        RecordingStore.Recording featured = recordings.get(0);
        String featuredId = currentId == null ? store.lastId() : currentId;
        for (RecordingStore.Recording recording : recordings) {
            if (recording.id().equals(featuredId)) {
                featured = recording;
                break;
            }
        }
        RecordingStore.Recording selected = featured;
        FrameLayout hero = new FrameLayout(this);
        hero.setBackground(gradient(0xff2d4638, 0xff1d332a, 24));
        hero.setClipToOutline(true);
        AudioView artwork = new AudioView(this, "disc", 0xff87a38a);
        FrameLayout.LayoutParams artParams = new FrameLayout.LayoutParams(dp(166), dp(166),
                Gravity.END | Gravity.CENTER_VERTICAL);
        artParams.setMarginEnd(-dp(20));
        hero.addView(artwork, artParams);
        LinearLayout info = column();
        info.setPadding(dp(19), dp(19), dp(83), dp(18));
        add(info, text(selected.id().equals("morning-walk") && selected.position() == 0
                ? "听力示例" : "继续收听", 13, ACCENT, true), 0);
        TextView featureTitle = text(selected.title(), 24, TEXT, true);
        featureTitle.setMaxLines(2);
        featureTitle.setEllipsize(TextUtils.TruncateAt.END);
        add(info, featureTitle, 10);
        playStatus = text(time(selected.position()) + " / " + time(selected.duration()), 13, MUTED, false);
        add(info, playStatus, 9);
        hero.addView(info, new FrameLayout.LayoutParams(-1, -2));
        hero.setOnClickListener(view -> openRecording(selected, false));
        playButton = icon("play", "播放", ACCENT, BACKGROUND, 50, () -> {
            if (selected.id().equals(currentId())) {
                togglePlayback();
            } else {
                openRecording(selected, true);
            }
        });
        FrameLayout.LayoutParams playParams = new FrameLayout.LayoutParams(dp(50), dp(50),
                Gravity.END | Gravity.CENTER_VERTICAL);
        playParams.setMarginEnd(dp(19));
        hero.addView(playButton, playParams);
        add(body, hero, 18);

        LinearLayout section = row();
        section.addView(text("全部录音", 18, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        section.addView(text(recordings.size() + " 段", 13, MUTED, false));
        add(body, section, 28);

        LinearLayout list = panel(SURFACE, 20, 0);
        add(body, list, 12);
        for (int index = 0; index < recordings.size(); index++) {
            RecordingStore.Recording recording = recordings.get(index);
            boolean active = recording.id().equals(currentId);
            LinearLayout item = row();
            item.setMinimumHeight(dp(72));
            item.setPadding(dp(12), dp(9), dp(10), dp(9));
            item.setBackground(ripple(SURFACE, 14));
            AudioView glyph = new AudioView(this, "mark", active ? ACCENT : MUTED);
            glyph.setBackground(round(active ? 0xff344638 : 0xff25362c, 12));
            item.addView(glyph, new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout labels = column();
            TextView title = text(recording.title(), 16, active ? ACCENT : TEXT, true);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            add(labels, title, 0);
            add(labels, text(time(recording.duration()), 13, MUTED, false), 3);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
            labelParams.setMarginStart(dp(12));
            item.addView(labels, labelParams);
            item.addView(icon("more", "编辑 " + recording.title(), 0xff233229, MUTED, 44,
                    () -> openImport(recording.id())));
            item.setOnClickListener(view -> openRecording(recording, true));
            add(list, item, 0);
            if (index + 1 < recordings.size()) {
                View line = new View(this);
                line.setBackgroundColor(BORDER);
                LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(1));
                lineParams.setMarginStart(dp(64));
                list.addView(line, lineParams);
            }
        }
    }

    private void renderPlayer() {
        LinearLayout header = toolbar();
        header.addView(icon("back", "返回听力库", SURFACE, TEXT, 46, () -> show(Screen.LIBRARY)));
        TextView heading = text("播放器", 16, TEXT, true);
        heading.setGravity(Gravity.CENTER);
        header.addView(heading, new LinearLayout.LayoutParams(0, -1, 1));
        RecordingStore.Recording recording = currentRecording();
        if (recording == null) {
            header.addView(new View(this), new LinearLayout.LayoutParams(dp(46), dp(46)));
            add(scrollingBody(), text("请先在听力库选择一段录音。", 16, MUTED, false), 24);
            return;
        }
        header.addView(icon("more", "编辑录音", SURFACE, TEXT, 46, () -> openImport(recording.id())));

        LinearLayout body = scrollingBody();
        LinearLayout card = panel(SURFACE, 24, 18);
        add(body, card, 15);
        TextView title = text(recording.title(), 25, TEXT, true);
        add(card, title, 0);

        timelineArt = new AudioView(this, "wave", ACCENT);
        timelineArt.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams waveform = new LinearLayout.LayoutParams(-1, dp(60));
        waveform.topMargin = dp(14);
        card.addView(timelineArt, waveform);
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setContentDescription("播放进度");
        seekBar.setPadding(0, 0, 0, 0);
        seekBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(BORDER));
        seekBar.setThumbTintList(ColorStateList.valueOf(ACCENT));
        card.addView(seekBar, new LinearLayout.LayoutParams(-1, dp(30)));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    long position = duration() * progress / bar.getMax();
                    elapsed.setText(time(position));
                    timelineArt.setProgress(progress / (float) bar.getMax());
                    if (!scrubbing && controller != null) {
                        controller.seekTo(position);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                scrubbing = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                if (controller != null) {
                    controller.seekTo(duration() * bar.getProgress() / bar.getMax());
                }
                scrubbing = false;
            }
        });
        LinearLayout times = row();
        elapsed = text("0:00", 12, MUTED, false);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1));
        total = text(time(recording.duration()), 12, MUTED, false);
        times.addView(total);
        add(card, times, 0);

        LinearLayout transport = row();
        transport.setGravity(Gravity.CENTER);
        transport.addView(icon("rewind", "后退 10 秒", 0xff26392e, TEXT, 52, () -> {
            if (controller != null) {
                controller.seekBack();
            }
        }));
        playButton = icon("play", "播放", ACCENT, BACKGROUND, 62, this::togglePlayback);
        addSpaced(transport, playButton, 16);
        addSpaced(transport, icon("forward", "前进 10 秒", 0xff26392e, TEXT, 52, () -> {
            if (controller != null) {
                controller.seekForward();
            }
        }), 16);
        add(card, transport, 11);

        LinearLayout options = row();
        options.addView(icon("previous", "上一段", 0xff26392e, TEXT, 44, () -> skipRecording(-1)));
        speedButton = pill(speedLabel(store.speed()), false, this::chooseSpeed);
        LinearLayout.LayoutParams speedParams = new LinearLayout.LayoutParams(0, -2, 1);
        speedParams.setMargins(dp(8), 0, dp(8), 0);
        options.addView(speedButton, speedParams);
        options.addView(icon("next", "下一段", 0xff26392e, TEXT, 44, () -> skipRecording(1)));
        add(card, options, 12);

        LinearLayout transcript = panel(SURFACE, 24, 18);
        add(body, transcript, 16);
        LinearLayout transcriptHeader = row();
        transcriptHeader.addView(text("英文原文", 18, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        transcriptToggle = pill(transcriptVisible ? "隐藏" : "显示", false, this::toggleTranscript);
        transcriptHeader.addView(transcriptToggle);
        add(transcript, transcriptHeader, 0);
        transcriptView = text(recording.transcript().isBlank() ? "还没有原文。可在右上角添加。" : recording.transcript(),
                17, recording.transcript().isBlank() ? MUTED : TEXT, false);
        transcriptView.setLineSpacing(dp(5), 1.08f);
        transcriptView.setTextIsSelectable(!recording.transcript().isBlank());
        add(transcript, transcriptView, 8);
        if (!transcriptVisible) {
            transcriptView.setVisibility(View.GONE);
        }
    }

    private void toggleTranscript() {
        transcriptVisible = !transcriptVisible;
        transcriptView.setVisibility(transcriptVisible ? View.VISIBLE : View.GONE);
        transcriptToggle.setText(transcriptVisible ? "隐藏" : "显示");
    }

    private void chooseSpeed() {
        if (controller == null) {
            return;
        }
        String[] labels = new String[SPEEDS.length];
        int checked = -1;
        for (int index = 0; index < SPEEDS.length; index++) {
            labels[index] = speedLabel(SPEEDS[index]);
            if (SPEEDS[index] == controller.getPlaybackParameters().speed) {
                checked = index;
            }
        }
        new AlertDialog.Builder(this).setTitle("播放速度")
                .setSingleChoiceItems(labels, checked, (dialog, selected) -> {
                    controller.setPlaybackSpeed(SPEEDS[selected]);
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void openRecording(RecordingStore.Recording recording, boolean play) {
        if (controller == null) {
            toast("播放器正在连接，请稍后再试。");
            return;
        }
        if (!recording.id().equals(currentId())) {
            int index = queueIndex(recording.id());
            if (index != C.INDEX_UNSET) {
                controller.seekTo(index, recording.position());
            } else {
                List<MediaItem> queue = new ArrayList<>();
                for (RecordingStore.Recording item : store.list()) {
                    if (item.id().equals(recording.id())) {
                        index = queue.size();
                    }
                    queue.add(RecordingStore.request(item));
                }
                if (index == C.INDEX_UNSET) {
                    return;
                }
                controller.setMediaItems(queue, index, recording.position());
            }
        }
        show(Screen.PLAYER);
        if (play) {
            startPlayback();
        }
    }

    private void skipRecording(int direction) {
        if (controller == null) {
            return;
        }
        int index = controller.getCurrentMediaItemIndex() + direction;
        if (index < 0 || index >= controller.getMediaItemCount()) {
            return;
        }
        RecordingStore.Recording recording = store.find(controller.getMediaItemAt(index).mediaId);
        if (recording != null) {
            openRecording(recording, controller.getPlayWhenReady());
        }
    }

    private void togglePlayback() {
        if (controller == null || controller.getCurrentMediaItem() == null) {
            return;
        }
        if (controller.getPlayWhenReady() && controller.getPlaybackState() != Player.STATE_ENDED) {
            controller.pause();
        } else {
            startPlayback();
        }
    }

    private void startPlayback() {
        if (controller.getPlaybackState() == Player.STATE_IDLE) {
            controller.prepare();
        } else if (controller.getPlaybackState() == Player.STATE_ENDED) {
            controller.seekToDefaultPosition();
        }
        controller.play();
    }

    private String currentId() {
        MediaItem current = controller == null ? null : controller.getCurrentMediaItem();
        return current == null ? null : current.mediaId;
    }

    private RecordingStore.Recording currentRecording() {
        String id = currentId();
        return id == null ? null : store.find(id);
    }

    private int queueIndex(String id) {
        if (controller != null) {
            for (int index = 0; index < controller.getMediaItemCount(); index++) {
                if (id.equals(controller.getMediaItemAt(index).mediaId)) {
                    return index;
                }
            }
        }
        return C.INDEX_UNSET;
    }

    private long duration() {
        long value = controller == null ? C.TIME_UNSET : controller.getDuration();
        if (value > 0) {
            return value;
        }
        RecordingStore.Recording recording = currentRecording();
        return recording == null ? 0 : recording.duration();
    }

    private void updateControls() {
        if (controller == null || playButton == null) {
            return;
        }
        boolean playing = controller.getPlayWhenReady() && controller.getPlaybackState() != Player.STATE_ENDED;
        playButton.setIcon(playing ? "pause" : "play");
        playButton.setContentDescription(playing ? "暂停" : "播放");
        long position = controller.getCurrentPosition();
        long duration = duration();
        if (playStatus != null && controller.getCurrentMediaItem() != null) {
            playStatus.setText(time(position) + " / " + time(duration));
        }
        if (seekBar != null) {
            seekBar.setEnabled(duration > 0);
            if (!scrubbing) {
                int progress = duration <= 0 ? 0 : (int) Math.min(1000, position * 1000 / duration);
                seekBar.setProgress(progress);
                timelineArt.setProgress(progress / 1000f);
                elapsed.setText(time(position));
            }
            total.setText(time(duration));
            speedButton.setText(speedLabel(controller.getPlaybackParameters().speed));
        }
    }

    private void openImport(String id) {
        returnScreen = screen;
        editId = id;
        draftAudio = null;
        draftFilename = "";
        draftTitle = "";
        draftTranscript = "";
        if (id != null) {
            RecordingStore.Recording recording = store.find(id);
            if (recording == null) {
                return;
            }
            draftTitle = recording.title();
            draftTranscript = recording.transcript();
        }
        show(Screen.IMPORT);
    }

    private void renderImport() {
        boolean editing = editId != null;
        LinearLayout header = toolbar();
        header.addView(icon("back", "返回", SURFACE, TEXT, 46, this::closeImport));
        TextView heading = text(editing ? "编辑录音" : "导入录音", 17, TEXT, true);
        heading.setGravity(Gravity.CENTER);
        header.addView(heading, new LinearLayout.LayoutParams(0, -1, 1));
        header.addView(new View(this), new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout body = scrollingBody();
        body.setFocusableInTouchMode(true);
        body.requestFocus();
        if (!editing) {
            add(body, text("音频文件", 16, TEXT, true), 12);
            fileButton = pill(draftFilename.isEmpty() ? "＋  选择音频文件" : draftFilename,
                    false, () -> chooseFile(AUDIO_PICKER));
            fileButton.setContentDescription("选择音频文件");
            fileButton.setMaxLines(2);
            add(body, fileButton, 8);
        }
        add(body, text("标题", 16, TEXT, true), 20);
        titleInput = field("录音标题", draftTitle, false, 120);
        add(body, titleInput, 8);

        LinearLayout labels = row();
        labels.addView(text("英文原文", 16, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        labels.addView(pill("导入 TXT", false, () -> chooseFile(TEXT_PICKER)));
        add(body, labels, 18);
        transcriptInput = field("粘贴或输入这段录音的英文原文（可选）", draftTranscript,
                true, RecordingStore.MAX_TEXT_LENGTH);
        add(body, transcriptInput, 7);
        if (editing) {
            TextView delete = pill("删除录音", false, this::confirmDelete);
            delete.setTextColor(0xffffb5a8);
            add(body, delete, 16);
        }

        LinearLayout footer = column();
        footer.setPadding(dp(20), dp(8), dp(20), dp(10));
        saveButton = pill(editing ? "保存修改" : "保存到听力库", true, this::saveDraft);
        add(footer, saveButton, 0);
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
    }

    private void chooseFile(int request) {
        if (busy) {
            return;
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(request == AUDIO_PICKER ? "audio/*" : "text/*");
        startActivityForResult(picker, request);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null || screen != Screen.IMPORT) {
            return;
        }
        Uri uri = data.getData();
        if (request == AUDIO_PICKER) {
            draftAudio = uri;
            draftFilename = store.displayName(uri);
            fileButton.setText(draftFilename);
            if (titleInput.getText().toString().isBlank()) {
                int extension = draftFilename.lastIndexOf('.');
                String title = extension > 0 ? draftFilename.substring(0, extension) : draftFilename;
                titleInput.setText(title);
            }
        } else if (request == TEXT_PICKER) {
            setBusy(true, "正在读取原文…");
            IMPORTER.execute(() -> {
                try {
                    String imported = readTranscript(uri);
                    runOnUiThread(() -> {
                        if (!isDestroyed()) {
                            transcriptInput.setText(imported);
                            setBusy(false, null);
                        }
                    });
                } catch (IOException | RuntimeException error) {
                    finishFailedImport(error);
                }
            });
        }
    }

    private String readTranscript(Uri uri) throws IOException {
        byte[] data;
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IOException("无法读取原文文件");
            }
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1; ) {
                if (out.size() + count > RecordingStore.MAX_TEXT_LENGTH * 4 + 3) {
                    throw new IOException("原文文件太长，请分段导入");
                }
                out.write(buffer, 0, count);
            }
            data = out.toByteArray();
        }
        boolean utf16 = data.length >= 2
                && ((data[0] == (byte) 0xff && data[1] == (byte) 0xfe)
                || (data[0] == (byte) 0xfe && data[1] == (byte) 0xff));
        String text = new String(data, utf16 ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8);
        if (text.startsWith("\ufeff")) {
            text = text.substring(1);
        }
        if (text.length() > RecordingStore.MAX_TEXT_LENGTH) {
            throw new IOException("原文文件太长，请分段导入");
        }
        return text;
    }

    private void captureDraft() {
        if (screen == Screen.IMPORT && titleInput != null && transcriptInput != null) {
            draftTitle = titleInput.getText().toString();
            draftTranscript = transcriptInput.getText().toString();
        }
    }

    private void saveDraft() {
        if (busy) {
            return;
        }
        captureDraft();
        if (draftTitle.isBlank()) {
            titleInput.setError("请输入录音标题");
            titleInput.requestFocus();
            return;
        }
        String title = draftTitle.strip();
        String transcript = draftTranscript.strip();
        if (editId != null) {
            store.edit(editId, title, transcript);
            int index = queueIndex(editId);
            if (index != C.INDEX_UNSET) {
                controller.replaceMediaItem(index, RecordingStore.request(store.find(editId)));
            }
            hideKeyboard();
            show(returnScreen);
            toast("已保存修改");
            return;
        }
        if (draftAudio == null) {
            toast("请先选择音频文件");
            chooseFile(AUDIO_PICKER);
            return;
        }
        Uri audio = draftAudio;
        hideKeyboard();
        setBusy(true, "正在导入…");
        IMPORTER.execute(() -> {
            try {
                RecordingStore.Recording recording = store.add(audio, title, transcript);
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        if (controller != null && queueIndex(recording.id()) == C.INDEX_UNSET) {
                            boolean empty = controller.getMediaItemCount() == 0;
                            controller.addMediaItem(0, RecordingStore.request(recording));
                            if (empty) {
                                controller.prepare();
                            }
                        }
                        setBusy(false, null);
                        show(Screen.LIBRARY);
                        toast("已添加到听力库");
                    }
                });
            } catch (IOException | RuntimeException error) {
                finishFailedImport(error);
            }
        });
    }

    private void finishFailedImport(Exception error) {
        runOnUiThread(() -> {
            if (!isDestroyed()) {
                setBusy(false, null);
                toast(error instanceof IOException ? error.getMessage() : "读取或保存失败，请重试。");
            }
        });
    }

    private void setBusy(boolean value, String status) {
        busy = value;
        saveButton.setEnabled(!value);
        saveButton.setAlpha(value ? .66f : 1f);
        saveButton.setText(status != null ? status : editId == null ? "保存到听力库" : "保存修改");
    }

    private void confirmDelete() {
        if (busy) {
            return;
        }
        new AlertDialog.Builder(this).setTitle("删除这段录音？")
                .setMessage("录音和原文将从听力库移除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    int index = queueIndex(editId);
                    if (index != C.INDEX_UNSET) {
                        controller.removeMediaItem(index);
                    }
                    store.delete(editId);
                    hideKeyboard();
                    show(Screen.LIBRARY);
                }).show();
    }

    private void closeImport() {
        if (!busy) {
            hideKeyboard();
            show(returnScreen);
        }
    }

    private void hideKeyboard() {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    private LinearLayout toolbar() {
        LinearLayout bar = row();
        bar.setPadding(dp(20), dp(5), dp(20), dp(5));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(62)));
        return bar;
    }

    private LinearLayout scrollingBody() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = column();
        body.setPadding(dp(20), 0, dp(20), dp(18));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        return body;
    }

    private LinearLayout panel(int color, int radius, int padding) {
        LinearLayout panel = column();
        panel.setBackground(round(color, radius));
        panel.setClipToOutline(true);
        panel.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        return panel;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView pill(String label, boolean filled, Runnable action) {
        TextView button = text(label, 14, filled ? BACKGROUND : TEXT, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(14), dp(6), dp(14), dp(6));
        button.setBackground(ripple(filled ? ACCENT : 0xff293b30, 14));
        button.setOnClickListener(view -> action.run());
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
        return button;
    }

    private EditText field(String hint, String value, boolean multiline, int maxLength) {
        EditText input = new EditText(this);
        input.setTextSize(16);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setHint(hint);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        input.setInputType(multiline ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackground(round(SURFACE, 14));
        input.setGravity(multiline ? Gravity.TOP : Gravity.CENTER_VERTICAL);
        input.setMinHeight(dp(multiline ? 147 : 50));
        input.setMaxLines(multiline ? 10 : 1);
        input.setText(value);
        return input;
    }

    private AudioView icon(String kind, String description, int background, int foreground, int size,
                           Runnable action) {
        AudioView button = new AudioView(this, kind, foreground);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        button.setContentDescription(description);
        button.setBackground(ripple(background, size / 2));
        button.setFocusable(true);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private void add(LinearLayout layout, View child, int marginTop) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(marginTop);
        layout.addView(child, params);
    }

    private void addSpaced(LinearLayout layout, View child, int marginStart) {
        LinearLayout.LayoutParams params = child.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) child.getLayoutParams() : new LinearLayout.LayoutParams(-2, -2);
        params.setMarginStart(dp(marginStart));
        layout.addView(child, params);
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private GradientDrawable gradient(int from, int to, int radius) {
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{from, to});
        background.setCornerRadius(dp(radius));
        return background;
    }

    private RippleDrawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x25ffffff), round(color, radius),
                round(Color.WHITE, radius));
    }

    private int dp(float units) {
        return Math.round(getResources().getDisplayMetrics().density * units);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static String time(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1_000;
        return seconds >= 3_600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String speedLabel(float speed) {
        return (speed == (int) speed ? Integer.toString((int) speed) : Float.toString(speed)) + "×";
    }
}
