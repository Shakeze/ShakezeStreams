package pk.cinejoy.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * A mouse-pointer overlay that is moved with the TV remote's D-pad.
 * It never consumes touch events, so taps dispatched underneath it reach the page.
 */
public class CursorView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();

    private float cx = -1, cy = -1;
    private boolean pressed = false;
    private final float scale;

    public CursorView(Context context) {
        super(context);
        scale = getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);

        fill.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);

        stroke.setColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.6f * scale);
        stroke.setStrokeJoin(Paint.Join.ROUND);

        ring.setColor(0xCC95FF50); // Cinejoy green
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3f * scale);

        // Classic arrow pointer, tip at (0,0), ~22dp tall.
        float s = scale;
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 20 * s);
        arrow.lineTo(5 * s, 15.5f * s);
        arrow.lineTo(8.5f * s, 23 * s);
        arrow.lineTo(11.5f * s, 21.5f * s);
        arrow.lineTo(8 * s, 14.5f * s);
        arrow.lineTo(14.5f * s, 14.5f * s);
        arrow.close();
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        return false; // never block the page
    }

    public void setPosition(float x, float y) {
        cx = x;
        cy = y;
        invalidate();
    }

    public float getCursorX() { return cx; }
    public float getCursorY() { return cy; }

    public void setPressed2(boolean p) {
        pressed = p;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (cx < 0 || cy < 0 || cx > w || cy > h) {
            cx = w / 2f;
            cy = h / 2f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (cx < 0) return;
        canvas.save();
        canvas.translate(cx, cy);
        canvas.drawCircle(0, 0, (pressed ? 12 : 18) * scale, ring);
        canvas.drawPath(arrow, fill);
        canvas.drawPath(arrow, stroke);
        canvas.restore();
    }
}
