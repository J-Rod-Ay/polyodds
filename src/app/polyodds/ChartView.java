package app.polyodds;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/** Translucent win-probability chart: one line per outcome, pulsing endpoints, odometer % labels. */
public class ChartView extends View {
    static final long PULSE_MS = 1600, ROLL_MS = 380, FRAME_MS = 40;

    final float d;
    final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG),
            name = new Paint(Paint.ANTI_ALIAS_FLAG), pct = new Paint(Paint.ANTI_ALIAS_FLAG);

    Game game;
    OverlayService.Series[] series;
    long from;          // chart left edge (ms)
    volatile long delayMs;
    final Odo[] odo = {new Odo(), new Odo(), new Odo()};

    public ChartView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        grid.setColor(Color.argb(55, 255, 255, 255));
        grid.setStrokeWidth(1);
        grid.setPathEffect(new DashPathEffect(new float[]{3 * d, 4 * d}, 0));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.2f * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        small.setTextSize(8.5f * d);
        small.setColor(Color.argb(175, 255, 255, 255));
        small.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        small.setLetterSpacing(0.06f);
        name.setTextSize(8.5f * d);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setLetterSpacing(0.05f);
        pct.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
    }

    void setGame(Game g, OverlayService.Series[] s, long from) {
        synchronized (this) {
            game = g; series = s; this.from = from;
            for (Odo o : odo) o.reset();
        }
        postInvalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        bg.setShader(new LinearGradient(0, 0, 0, h, Color.argb(150, 6, 8, 14), Color.argb(200, 6, 8, 14), Shader.TileMode.CLAMP));
    }

    @Override
    protected synchronized void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), pad = 10 * d;
        c.drawRoundRect(new RectF(0, 0, w, h), 12 * d, 12 * d, bg);
        float scale = h / (145 * d);
        pct.setTextSize(15 * d * scale);

        float labelW = Math.max(w * 0.27f, 74 * d);
        float l = pad, r = w - labelW, top = pad + 16 * d, bot = h - pad - 2 * d;
        long now = System.currentTimeMillis(), cutoff = now - delayMs;

        // header: brand + delay
        c.drawText("POLYMARKET  ·  LIVE ODDS", l, pad + 7 * d, small);
        if (delayMs > 0) {
            String dl = "−" + delayMs / 1000 + "s";
            c.drawText(dl, w - pad - small.measureText(dl), pad + 7 * d, small);
        }
        for (double g : new double[]{0.25, 0.5, 0.75}) {
            float y = (float) (bot - g * (bot - top));
            c.drawLine(l, y, r, y, grid);
        }

        if (game == null || series == null) return;
        int k = series.length;
        long t0 = from, t1 = Math.max(cutoff, t0 + 60_000);

        // fixed clock grid: buckets aligned to wall-clock multiples so old steps don't shimmer as the window grows
        int maxPts = Math.max(20, (int) ((r - l) / (2.5f * d)));
        long bucket = Math.max(15_000, ((t1 - t0) / maxPts + 14_999) / 15_000 * 15_000);
        long b0 = t0 / bucket * bucket;
        float[] endY = new float[k];
        double[] endP = new double[k];
        boolean any = false;
        for (int i = k - 1; i >= 0; i--) {
            double[] v = series[i].bucketed(b0, t1, bucket);
            double last = series[i].at(cutoff);
            if (v == null || Double.isNaN(last)) { endP[i] = Double.NaN; continue; }
            any = true;
            Path path = new Path();
            boolean started = false;
            for (int j = 0; j < v.length; j++) {
                if (Double.isNaN(v[j])) continue;
                double m = med(v[Math.max(0, j - 1)], v[j], v[Math.min(v.length - 1, j + 1)]);
                long tj = Math.max(t0, b0 + j * bucket);
                float x = l + (r - l) * (Math.min(tj, t1) - t0) / (float) (t1 - t0);
                float y = (float) (bot - m * (bot - top));
                if (!started) { path.moveTo(x, y); started = true; } else path.lineTo(x, y);
            }
            float yEnd = (float) (bot - last * (bot - top));
            if (started) path.lineTo(r, yEnd); else path.moveTo(r, yEnd);
            line.setColor(game.colors[i]);
            c.drawPath(path, line);
            endY[i] = yEnd;
            endP[i] = last;
        }
        if (!any) {
            pct.setTextSize(11 * d);
            pct.setColor(Color.argb(200, 255, 255, 255));
            c.drawText("loading " + game.title + "…", l, (top + bot) / 2, pct);
            postInvalidateDelayed(500);
            return;
        }

        // pulsing endpoints
        float ph = (SystemClock.uptimeMillis() % PULSE_MS) / (float) PULSE_MS;
        for (int i = 0; i < k; i++) {
            if (Double.isNaN(endP[i])) continue;
            dot.setColor(game.colors[i]);
            dot.setAlpha((int) (150 * (1 - ph)));
            c.drawCircle(r, endY[i], (3 + 7 * ph) * d, dot);
            dot.setAlpha(255);
            c.drawCircle(r, endY[i], 3 * d, dot);
        }

        // labels, pushed apart so they never overlap
        float gap = 28 * d * scale;
        final float[] ly = endY.clone();
        Integer[] order = new Integer[k];
        for (int i = 0; i < k; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Float.compare(ly[a], ly[b]));
        for (int pass = 0; pass < 4; pass++)
            for (int j = 1; j < k; j++) {
                int a = order[j - 1], b = order[j];
                if (ly[b] - ly[a] < gap) { float mid = (ly[a] + ly[b]) / 2; ly[a] = mid - gap / 2; ly[b] = mid + gap / 2; }
            }
        for (int i = 0; i < k; i++) {
            if (Double.isNaN(endP[i])) continue;
            float y = Math.max(top + 6 * d, Math.min(bot - 12 * d * scale, ly[i]));
            float x = r + 9 * d;
            name.setColor(game.colors[i]);
            c.drawText(game.shorts[i].toUpperCase(), x, y - 2 * d, name);
            pct.setColor(game.colors[i]);
            odo[i].draw(c, pct, Math.round(endP[i] * 100) + "%", x, y + 14 * d * scale);
        }
        postInvalidateDelayed(FRAME_MS);
    }

    static double med(double a, double b, double c) {
        if (Double.isNaN(a)) a = b;
        if (Double.isNaN(c)) c = b;
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    /** Odometer: changed digits roll up (uptick) or down (downtick) with a small overshoot. */
    static final class Odo {
        String shown = "", prev = "";
        long at;
        int dir;

        void reset() { shown = ""; prev = ""; at = 0; }

        void draw(Canvas c, Paint p, String s, float x, float base) {
            long now = SystemClock.uptimeMillis();
            if (!s.equals(shown)) {
                if (!shown.isEmpty()) {
                    prev = shown;
                    at = now;
                    dir = Integer.parseInt(s.replace("%", "")) > Integer.parseInt(shown.replace("%", "")) ? 1 : -1;
                }
                shown = s;
            }
            float t = Math.min(1f, (now - at) / (float) ROLL_MS);
            if (t >= 1f || prev.isEmpty()) { c.drawText(s, x, base, p); return; }
            float e = overshoot(t);
            int n = Math.max(s.length(), prev.length());
            String a = pad(prev, n), b = pad(s, n);
            float cw = p.measureText("0"), hgt = p.getTextSize();
            float cx = x - (n - s.length()) * cw;
            c.save();
            c.clipRect(cx, base - hgt, x + n * cw * 2, base + hgt * 0.25f);
            for (int i = 0; i < n; i++) {
                char ca = a.charAt(i), cb = b.charAt(i);
                float w = cb == '%' ? p.measureText("%") : cw;
                if (ca == cb) { if (cb != ' ') c.drawText(String.valueOf(cb), cx, base, p); }
                else {
                    // uptick: new digit rises from below, old one exits upward
                    float off = hgt * (1 - e) * dir;
                    if (cb != ' ') c.drawText(String.valueOf(cb), cx, base + off, p);
                    if (ca != ' ') c.drawText(String.valueOf(ca), cx, base + off - hgt * dir, p);
                }
                cx += w;
            }
            c.restore();
        }

        static String pad(String s, int n) {
            StringBuilder b = new StringBuilder();
            for (int i = s.length(); i < n; i++) b.append(' ');
            return b.append(s).toString();
        }

        static float overshoot(float t) { // ~6% overshoot, settles by t=1
            float s = 1.7f;
            t -= 1;
            return t * t * ((s + 1) * t + s) + 1;
        }
    }
}
