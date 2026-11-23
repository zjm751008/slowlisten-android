package app.slowlisten.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

final class AudioView extends View {
    private static final int[] WAVE = {
            11, 17, 24, 15, 29, 37, 25, 15, 33, 48, 34, 19, 27, 39,
            55, 35, 19, 29, 45, 54, 36, 21, 31, 50, 42, 28, 18, 36,
            47, 28, 19, 30, 39, 24, 13, 25, 36, 23, 16, 21, 10
    };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int color;
    private final float density;
    private String icon;
    private float progress;

    AudioView(Context context, String icon, int color) {
        super(context);
        this.icon = icon;
        this.color = color;
        density = getResources().getDisplayMetrics().density;
    }

    void setIcon(String value) {
        if (!icon.equals(value)) {
            icon = value;
            invalidate();
        }
    }

    void setProgress(float value) {
        if (progress != value) {
            progress = value;
            invalidate();
        }
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return isClickable() ? "android.widget.ImageButton" : super.getAccessibilityClassName();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        if ("wave".equals(icon)) {
            drawWave(canvas);
            return;
        }
        if ("disc".equals(icon)) {
            drawDisc(canvas);
            return;
        }

        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(density, density);
        paint.setStrokeWidth(1.8f);

        switch (icon) {
            case "mark" -> {
                bar(canvas, -9, 5);
                bar(canvas, -3, 10);
                bar(canvas, 3, 7);
                bar(canvas, 9, 4);
            }
            case "play" -> triangle(canvas, -5, -10, 11, 0, -5, 10);
            case "pause" -> {
                canvas.drawRoundRect(-7, -9, -2, 9, 1.5f, 1.5f, paint);
                canvas.drawRoundRect(2, -9, 7, 9, 1.5f, 1.5f, paint);
            }
            case "back" -> {
                canvas.drawLine(-9, 0, 8, 0, paint);
                canvas.drawLine(-9, 0, -2, -7, paint);
                canvas.drawLine(-9, 0, -2, 7, paint);
            }
            case "more" -> {
                canvas.drawCircle(-6, 0, 1.55f, paint);
                canvas.drawCircle(0, 0, 1.55f, paint);
                canvas.drawCircle(6, 0, 1.55f, paint);
            }
            case "rewind", "forward" -> drawTen(canvas, "forward".equals(icon));
            case "previous" -> {
                triangle(canvas, 6, -6, -5, 0, 6, 6);
                canvas.drawLine(-7, -6, -7, 6, paint);
            }
            case "next" -> {
                triangle(canvas, -6, -6, 5, 0, -6, 6);
                canvas.drawLine(7, -6, 7, 6, paint);
            }
            default -> { }
        }
        canvas.restore();
    }

    private void drawWave(Canvas canvas) {
        float step = getWidth() / (float) WAVE.length;
        float center = getHeight() / 2f;
        paint.setStrokeWidth(Math.min(step * .42f, 3.2f * density));
        for (int i = 0; i < WAVE.length; i++) {
            float x = step * (i + .5f);
            float h = getHeight() * WAVE[i] / 120f;
            paint.setColor(i <= (progress * WAVE.length) - 0.5f ? color : 0xff657d68);
            canvas.drawLine(x, center - h, x, center + h, paint);
        }
    }

    private void drawDisc(Canvas canvas) {
        float radius = Math.min(getWidth(), getHeight()) / 2f;
        float x = getWidth() / 2f;
        float y = getHeight() / 2f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        for (float ratio : new float[]{.37f, .5f, .63f, .76f, .9f}) {
            paint.setAlpha(60);
            canvas.drawCircle(x, y, radius * ratio, paint);
        }
        paint.setAlpha(110);
        canvas.drawArc(x - radius * .78f, y - radius * .78f, x + radius * .78f,
                y + radius * .78f, 166, 108, false, paint);
        canvas.drawArc(x - radius * .52f, y - radius * .52f, x + radius * .52f,
                y + radius * .52f, -43, 106, false, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(125);
        canvas.drawCircle(x, y, radius * .11f, paint);
        paint.setAlpha(255);
    }

    private void drawTen(Canvas canvas, boolean forward) {
        canvas.save();
        if (!forward) {
            canvas.scale(-1, 1);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.55f);
        canvas.drawArc(-9.4f, -9.4f, 9.4f, 9.4f, -132, 292, false, paint);
        canvas.drawLine(6, -7, 7.8f, -10.7f, paint);
        canvas.drawLine(6, -7, 10.3f, -6.5f, paint);
        canvas.restore();
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(9f);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("10", 0, 3.4f, paint);
    }

    private void triangle(Canvas canvas, float x1, float y1, float x2, float y2, float x3, float y3) {
        path.rewind();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void bar(Canvas canvas, int x, int halfHeight) {
        paint.setStrokeWidth(2.4f);
        canvas.drawLine(x, -halfHeight, x, halfHeight, paint);
    }
}
