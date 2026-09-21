package com.batterymonitor.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 极简折线图（自绘，无第三方依赖）。
 * 支持：单指左右拖动平移时间线、双指捏合缩放（拉宽/收窄时间线）、双击复位适配全宽。
 */
public class BatteryChart extends View {
    private static final int PAD_L = 56, PAD_R = 14, PAD_T = 14, PAD_B = 30;

    /** 视口变化回调：用于让多个图表保持同一时间窗口（命名接口，避免匿名类触发 R8 内部错误） */
    public interface ViewportListener {
        void onViewportChanged(float scale, float offsetSec);
    }

    private ViewportListener viewportListener;

    private float[] xs;          // 相对首点的秒数
    private float[] ys;          // 数值
    private long t0Abs;          // 首点绝对时间戳(秒)，用于 X 轴刻度
    private Float refLine;       // 参考线（如设计容量）

    // 视口状态
    private float scaleX = 0;    // px/秒；0 = 自适应全宽
    private float offsetSec = 0; // 视口左端对应的数据秒数（相对 t0Abs）

    // 触摸状态
    private int touchMode = 0;   // 0空闲 1单指 2双指
    private float lastX;
    private float lastDist;
    private long lastTapUpAt;
    private float lastTapX, lastTapY;

    private final Paint linePaint = new Paint();
    private final Paint refPaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint textPaint = new Paint();

    public BatteryChart(Context c) { super(c); init(); }
    public BatteryChart(Context c, AttributeSet a) { super(c, a); init(); }
    public BatteryChart(Context c, AttributeSet a, int style) { super(c, a, style); init(); }

    private void init() {
        linePaint.setColor(0xFF2F7DF6);
        // 必须显式 STROKE：Paint 默认 FILL，drawPath 会把折线闭合填充成多边形（蝴蝶结状）
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3f);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setAntiAlias(true);
        refPaint.setColor(0xFF8A5CF6);
        refPaint.setStrokeWidth(2f);
        refPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8, 6}, 0));
        gridPaint.setColor(0xFFE6EAF2);
        gridPaint.setStrokeWidth(1f);
        textPaint.setColor(0xFF9AA3B2);
        textPaint.setTextSize(22f);
        textPaint.setAntiAlias(true);
    }

    public void setData(long[] ts, float[] vals, Float ref) {
        if (ts == null || vals == null || ts.length == 0) {
            xs = null; ys = null; refLine = ref; invalidate(); return;
        }
        // 时间戳以「相对首点秒数」保存：绝对秒(1.8e9)转 float 只有 128s 精度，
        // 会把相邻采样点挤到同一竖线上；相对值在数天范围内可被 float 精确表示
        long t0 = ts[0];
        t0Abs = t0;
        xs = new float[ts.length];
        ys = new float[vals.length];
        for (int i = 0; i < ts.length; i++) { xs[i] = ts[i] - t0; ys[i] = vals[i]; }
        refLine = ref;
        clampViewport();
        invalidate();
    }

    private float xWidth() { return getWidth() - PAD_L - PAD_R; }

    private float totalSpan() {
        return (xs == null || xs.length < 2) ? 0 : xs[xs.length - 1];
    }

    private float fitScale() {
        float span = totalSpan();
        return span > 0 ? xWidth() / span : 1f;
    }

    /** 当前生效的横向比例（fit 模式即时计算） */
    private float effScale() {
        return scaleX > 0 ? scaleX : fitScale();
    }

    private void clampViewport() {
        float span = totalSpan();
        if (span <= 0) { offsetSec = 0; return; }
        float sx = effScale();
        float viewSpan = xWidth() / sx;
        if (viewSpan >= span) {
            scaleX = 0;          // 缩得太小就回到自适应全宽
            offsetSec = 0;
        } else {
            float maxOff = span - viewSpan;
            if (offsetSec < 0) offsetSec = 0;
            if (offsetSec > maxOff) offsetSec = maxOff;
        }
    }

    // ---------------- 触摸交互 ----------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (xs == null || xs.length < 2) return false;
        final int act = e.getActionMasked();
        switch (act) {
            case MotionEvent.ACTION_DOWN:
                // 双击复位（300ms 内第二次按下）
                long now = System.currentTimeMillis();
                if (now - lastTapUpAt < 300
                        && Math.abs(e.getX() - lastTapX) < 40
                        && Math.abs(e.getY() - lastTapY) < 40) {
                    scaleX = 0;
                    offsetSec = 0;
                    viewportChanged();
                    lastTapUpAt = 0;
                    return true;
                }
                touchMode = 1;
                lastX = e.getX();
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() >= 2) {
                    touchMode = 2;
                    lastDist = dist(e);
                }
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (touchMode == 2 && e.getPointerCount() >= 2) {
                    float d = dist(e);
                    if (lastDist > 0 && d > 0) {
                        float sx = effScale();
                        float newSx = clampScale(sx * (d / lastDist));
                        // 以两指中点为缩放焦点
                        float focusX = (e.getX(0) + e.getX(1)) / 2f;
                        float focusSec = (focusX - PAD_L) / sx + offsetSec;
                        scaleX = newSx;
                        offsetSec = focusSec - (focusX - PAD_L) / newSx;
                        clampViewport();
                        viewportChanged();
                    }
                    lastDist = d;
                } else if (touchMode == 1) {
                    float dx = e.getX() - lastX;
                    lastX = e.getX();
                    if (Math.abs(dx) > 0.5f) {
                        if (scaleX <= 0) scaleX = fitScale();  // 从 fit 进入显式缩放
                        offsetSec -= dx / effScale();
                        clampViewport();
                        viewportChanged();
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerCount() <= 2) touchMode = 1;
                if (touchMode == 1) lastX = e.getX(0);
                return true;

            case MotionEvent.ACTION_UP:
                if (touchMode == 1) {
                    // 记录抬起位置用于双击判定（位移很小时才算点击）
                    long now2 = System.currentTimeMillis();
                    if (now2 - lastTapUpAt >= 0) {
                        lastTapUpAt = now2;
                        lastTapX = e.getX();
                        lastTapY = e.getY();
                    }
                }
                touchMode = 0;
                lastDist = 0;
                return true;

            case MotionEvent.ACTION_CANCEL:
                touchMode = 0;
                lastDist = 0;
                return true;
        }
        return super.onTouchEvent(e);
    }

    private float dist(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // ---------------- 程序化视口控制（供快捷按钮调用） ----------------

    public void setViewportListener(ViewportListener l) {
        viewportListener = l;
    }

    /** 应用视口（供多图同步调用），内部会做边界收敛 */
    public void setViewport(float scale, float off) {
        scaleX = scale;
        offsetSec = off;
        clampViewport();
        invalidate();
    }

    private void viewportChanged() {
        invalidate();
        if (viewportListener != null) viewportListener.onViewportChanged(scaleX, offsetSec);
    }

    /** 复位：自适应全宽，显示全部数据 */
    public void resetView() {
        scaleX = 0;
        offsetSec = 0;
        viewportChanged();
    }

    /** 以视口中心为焦点缩放；factor > 1 表示拉宽时间线（放大细节） */
    public void zoomBy(float factor) {
        if (xs == null || xs.length < 2) return;
        float sx = effScale();
        float centerSec = offsetSec + (xWidth() / 2f) / sx;
        float newSx = clampScale(sx * factor);
        scaleX = newSx;
        offsetSec = centerSec - (xWidth() / 2f) / newSx;
        clampViewport();
        viewportChanged();
    }

    /** 只看「最近 secs 秒」；secs <= 0 等价于复位到全部 */
    public void showRange(float secs) {
        if (xs == null || xs.length < 2) return;
        if (secs <= 0) { resetView(); return; }
        float s = xWidth() / secs;
        if (s <= fitScale()) { resetView(); return; }
        scaleX = clampScale(s);
        float viewSpan = xWidth() / scaleX;
        offsetSec = Math.max(0, totalSpan() - viewSpan);   // 锚定到最新数据
        clampViewport();
        viewportChanged();
    }

    /** 缩放范围：最小=自适应全宽，最大=整个视口只显示 60 秒 */
    private float clampScale(float s) {
        float fit = fitScale();
        if (s < fit) s = fit;
        float max = xWidth() / 60f;
        if (s > max) s = max;
        return s;
    }

    // ---------------- 绘制 ----------------

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int W = getWidth(), H = getHeight();
        if (W == 0 || H == 0) return;
        cv.drawColor(0xFFFFFFFF);

        if (xs == null || xs.length < 2) {
            cv.drawText("数据不足", PAD_L, H / 2, textPaint);
            return;
        }
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float y : ys) {
            if (Float.isNaN(y) || Float.isInfinite(y)) continue;   // 无效点不参与范围计算
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        if (minY > maxY) { cv.drawText("数据不足", PAD_L, H / 2, textPaint); return; }
        if (refLine != null) { minY = Math.min(minY, refLine); maxY = Math.max(maxY, refLine); }
        if (minY == maxY) { minY -= 1; maxY += 1; }
        float padY = (maxY - minY) * 0.1f; minY -= padY; maxY += padY;

        float span = totalSpan();
        float sx = effScale();
        float viewSpan = xWidth() / sx;
        if (viewSpan >= span) { scaleX = 0; offsetSec = 0; sx = fitScale(); viewSpan = span; }
        float off = offsetSec;
        float xw = xWidth(), yh = H - PAD_T - PAD_B;

        // 网格 + Y 刻度
        for (int i = 0; i <= 4; i++) {
            float yv = minY + (maxY - minY) * i / 4;
            float yy = PAD_T + (1 - i / 4f) * yh;
            cv.drawLine(PAD_L, yy, W - PAD_R, yy, gridPaint);
            cv.drawText(String.format(Locale.US, "%.0f", yv), 4, yy + 6, textPaint);
        }
        // X 刻度：按可见时间跨度自适应选择整间隔（分钟/小时/天）
        SimpleDateFormat sdf;
        float target = (xw / 6f) / sx;   // 约 6 个刻度
        long[] nice = {60, 300, 600, 1800, 3600, 2 * 3600, 6 * 3600, 12 * 3600, 86400};
        long stepSec = nice[nice.length - 1];
        for (long n : nice) { if (n >= target) { stepSec = n; break; } }
        sdf = (stepSec >= 86400)
                ? new SimpleDateFormat("MM-dd", Locale.US)
                : new SimpleDateFormat("HH:mm", Locale.US);
        long firstTick = (long) Math.ceil(off / stepSec) * stepSec;
        for (long tv = firstTick; tv <= off + viewSpan; tv += stepSec) {
            float xx = PAD_L + (tv - off) * sx;
            if (xx < PAD_L - 1 || xx > W - PAD_R + 1) continue;
            cv.drawLine(xx, PAD_T, xx, H - PAD_B, gridPaint);
            cv.drawText(sdf.format(new Date((t0Abs + tv) * 1000L)), xx - 18, H - 8, textPaint);
        }
        // 参考线
        if (refLine != null) {
            float yy = PAD_T + (1 - (refLine - minY) / (maxY - minY)) * yh;
            cv.drawLine(PAD_L, yy, W - PAD_R, yy, refPaint);
        }
        // 折线（裁剪到绘图区，平移时不越界）
        cv.save();
        cv.clipRect(PAD_L, PAD_T, W - PAD_R, H - PAD_B);
        android.graphics.Path path = new android.graphics.Path();
        boolean penDown = false;
        for (int i = 0; i < xs.length; i++) {
            float v = ys[i];
            if (Float.isNaN(v) || Float.isInfinite(v)) { penDown = false; continue; }
            float px = PAD_L + (xs[i] - off) * sx;
            if (px < PAD_L - xw) { penDown = false; continue; }   // 远在视口左侧，跳过
            if (px > W - PAD_R + xw) break;                        // 远在视口右侧，结束
            float py = PAD_T + (1 - (v - minY) / (maxY - minY)) * yh;
            if (!penDown) { path.moveTo(px, py); penDown = true; } else path.lineTo(px, py);
        }
        cv.drawPath(path, linePaint);
        cv.restore();
    }
}
