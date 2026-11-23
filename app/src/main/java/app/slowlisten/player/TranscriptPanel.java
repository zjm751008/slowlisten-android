package app.slowlisten.player;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class TranscriptPanel extends LinearLayout {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final int TRANSCRIPT_HEIGHT_DP = 280;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RecordingStore store;
    private final RecordingStore.Recording recording;
    private final ParagraphAlignment.Transcript transcript;
    private final TextView status;
    private final TextView action;
    private final ScrollView scroll;
    private final TextView text;
    private final BackgroundColorSpan highlight = new BackgroundColorSpan(0xff3d5337);
    private final ForegroundColorSpan emphasis = new ForegroundColorSpan(MainActivity.ACCENT);

    private List<ParagraphAlignment.Cue> cues;
    private Future<?> job;
    private int generation;
    private int percent = -1;
    private boolean failed;
    private boolean following = true;
    private long position;
    private int activeParagraph = -1;
    private int scrolledParagraph = -1;

    TranscriptPanel(Context context, RecordingStore store, RecordingStore.Recording recording) {
        super(context);
        this.store = store;
        this.recording = recording;
        transcript = ParagraphAlignment.parse(recording.transcript());
        cues = ParagraphAlignment.decode(recording.alignment(), transcript.paragraphs().size());
        setOrientation(VERTICAL);

        LinearLayout statusLine = new LinearLayout(context);
        statusLine.setGravity(Gravity.CENTER_VERTICAL);
        status = label(context, 13, MainActivity.MUTED);
        statusLine.addView(status, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        action = label(context, 13, MainActivity.ACCENT);
        action.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        action.setGravity(Gravity.CENTER);
        action.setMinimumHeight(dp(48));
        action.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xff293b30);
        shape.setCornerRadius(dp(12));
        action.setBackground(new RippleDrawable(ColorStateList.valueOf(0x25ffffff), shape, null));
        action.setFocusable(true);
        action.setOnClickListener(view -> {
            if (cues == null || cues.isEmpty()) {
                startAlignment();
            } else {
                seekTo(position);
            }
        });
        statusLine.addView(action);
        addView(statusLine, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        statusLine.setVisibility(transcript.text().isEmpty() ? GONE : VISIBLE);

        scroll = new TranscriptScrollView(context);
        scroll.setNestedScrollingEnabled(true);
        scroll.setVerticalScrollBarEnabled(false);
        text = label(context, 17, transcript.text().isEmpty() ? MainActivity.MUTED : MainActivity.TEXT);
        text.setLineSpacing(dp(5), 1.08f);
        text.setTextIsSelectable(!transcript.text().isEmpty());
        text.setText(transcript.text().isEmpty() ? "还没有原文。可在右上角添加。" : transcript.text(),
                TextView.BufferType.SPANNABLE);
        text.setPadding(0, dp(7), 0, dp(8));
        scroll.addView(text, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LayoutParams scrollParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = dp(7);
        addView(scroll, scrollParams);

        updateStatus();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        maybeStart();
    }

    @Override
    protected void onDetachedFromWindow() {
        generation++;
        if (job != null) {
            job.cancel(true);
            job = null;
        }
        super.onDetachedFromWindow();
    }

    void setShown(boolean shown) {
        setVisibility(shown ? VISIBLE : GONE);
        if (shown) {
            seekTo(position);
            maybeStart();
        }
    }

    void seekTo(long positionMs) {
        following = true;
        scrolledParagraph = -1;
        updateStatus();
        updatePosition(positionMs);
    }

    void updatePosition(long positionMs) {
        position = positionMs;
        int paragraph = cues == null ? -1 : ParagraphAlignment.paragraphAt(cues, positionMs);
        if (paragraph != activeParagraph) {
            activeParagraph = paragraph;
            updateHighlight();
        }
        if (following && paragraph >= 0 && paragraph != scrolledParagraph && isShown()) {
            Layout layout = text.getLayout();
            if (layout != null) {
                int line = layout.getLineForOffset(transcript.paragraphs().get(paragraph).start());
                int y = text.getTop() + text.getTotalPaddingTop() + layout.getLineTop(line);
                scroll.smoothScrollTo(0, Math.max(0, y - dp(12)));
                scrolledParagraph = paragraph;
            }
        }
    }

    private void updateHighlight() {
        if (!(text.getText() instanceof Spannable content)) {
            return;
        }
        content.removeSpan(highlight);
        content.removeSpan(emphasis);
        if (activeParagraph >= 0) {
            ParagraphAlignment.Paragraph paragraph = transcript.paragraphs().get(activeParagraph);
            content.setSpan(highlight, paragraph.start(), paragraph.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            content.setSpan(emphasis, paragraph.start(), paragraph.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void maybeStart() {
        if (cues == null && !failed && job == null && !transcript.text().isEmpty()
                && getVisibility() == VISIBLE && isAttachedToWindow()) {
            startAlignment();
        }
    }

    private void startAlignment() {
        if (job != null) {
            return;
        }
        Context context = getContext().getApplicationContext();
        int request = ++generation;
        failed = false;
        cues = null;
        percent = -1;
        updateStatus();
        job = WORKER.submit(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                List<ParagraphAlignment.Cue> result = SpeechAlignment.align(context, store, recording,
                        transcript, value -> main.post(() -> {
                            if (request == generation) {
                                percent = value;
                                updateStatus();
                            }
                        }));
                if (!Thread.currentThread().isInterrupted()) {
                    store.saveAlignment(recording, ParagraphAlignment.encode(result));
                    main.post(() -> complete(request, result));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException | LinkageError error) {
                Log.w("TranscriptPanel", "Local subtitle alignment failed", error);
                main.post(() -> complete(request, null));
            }
        });
    }

    private void complete(int request, List<ParagraphAlignment.Cue> result) {
        if (request != generation) {
            return;
        }
        job = null;
        failed = result == null;
        cues = result;
        updateStatus();
        updatePosition(position);
    }

    private void updateStatus() {
        action.setVisibility(GONE);
        if (failed) {
            status.setText("字幕对齐失败");
            showAction("重试");
        } else if (cues == null) {
            status.setText(percent < 0 ? "准备在手机上对齐…" : getResources().getString(R.string.subtitle_progress, percent));
        } else if (cues.isEmpty()) {
            status.setText("未找到足够匹配的文字");
            showAction("重试");
        } else if (!following) {
            status.setText("已暂停自动滚动");
            showAction("继续跟随");
        } else if (cues.size() < transcript.paragraphs().size()) {
            status.setText(getResources().getString(R.string.subtitle_partial,
                    cues.size(), transcript.paragraphs().size()));
        } else {
            status.setText("跟随播放");
        }
    }

    private void showAction(String title) {
        action.setText(title);
        action.setVisibility(VISIBLE);
    }

    private TextView label(Context context, int size, int color) {
        TextView view = new TextView(context);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private final class TranscriptScrollView extends ScrollView {
        private final int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        private float startY;

        TranscriptScrollView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int maxHeight = dp(TRANSCRIPT_HEIGHT_DP);
            if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
                maxHeight = Math.min(maxHeight, MeasureSpec.getSize(heightSpec));
            }
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                startY = event.getY();
                if (canScrollVertically(1) || canScrollVertically(-1)) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && following && cues != null
                    && !cues.isEmpty() && Math.abs(event.getY() - startY) > touchSlop) {
                following = false;
                updateStatus();
            }
            return super.dispatchTouchEvent(event);
        }
    }
}
