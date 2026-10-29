package app.shoosh;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;

/** The big ring in the middle of the screen. Progress runs from 0 to 1. */
public class DialView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float stroke;
    private float progress = 1f;

    public DialView(Context context) {
        this(context, null);
    }

    public DialView(Context context, AttributeSet attrs) {
        super(context, attrs);
        stroke = 14f * getResources().getDisplayMetrics().density;

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(stroke);
        trackPaint.setColor(context.getColor(R.color.track));

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(context.getColor(R.color.surface));
    }

    public void setProgress(float p) {
        float clamped = Math.max(0f, Math.min(1f, p));
        if (clamped != progress) {
            progress = clamped;
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float size = Math.min(w, h);
        float inset = stroke / 2f + 2f;
        float left = (w - size) / 2f;
        float top = (h - size) / 2f;
        oval.set(left + inset, top + inset, left + size - inset, top + size - inset);

        int start = getContext().getColor(R.color.accent_end);
        int end = getContext().getColor(R.color.accent);
        SweepGradient shader = new SweepGradient(w / 2f, h / 2f,
                new int[] {start, end, start}, new float[] {0f, 0.5f, 1f});
        Matrix m = new Matrix();
        m.setRotate(-90f, w / 2f, h / 2f);
        shader.setLocalMatrix(m);
        arcPaint.setShader(shader);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawArc(oval, 0f, 360f, false, trackPaint);
        if (progress <= 0f) {
            return;
        }
        float sweep = 360f * progress;
        canvas.drawArc(oval, -90f, sweep, false, arcPaint);

        // Small knob at the leading edge of the arc.
        double angle = Math.toRadians(-90f + sweep);
        float r = oval.width() / 2f;
        float cx = oval.centerX() + (float) (r * Math.cos(angle));
        float cy = oval.centerY() + (float) (r * Math.sin(angle));
        canvas.drawCircle(cx, cy, stroke * 0.22f, dotPaint);
    }
}
