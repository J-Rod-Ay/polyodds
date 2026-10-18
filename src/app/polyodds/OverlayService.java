package app.polyodds;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The brain. Owns the overlay window, the live price feed (websocket + REST fallback), the phone
 * control server and auto-detection. Runs as a foreground service; the overlay itself can be hidden.
 */
public class OverlayService extends Service {
    static final String TAG = "polyodds";
    static final int[] DELAYS = {0, 5, 10, 15, 20, 30, 45, 60, 90, 120};
    static volatile OverlayService instance;

    final Handler main = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    WindowManager wm;
    ChartView view;
    ControlServer server;
    volatile Game game;
    volatile Series[] series;
    Thread feed;
    WsClient ws;
    long lastDetect;
    String detectNote = "";

    /** Price history of one outcome: one sample per second at most. */
    static final class Series {
        final ArrayList<Long> t = new ArrayList<>();
        final ArrayList<Double> p = new ArrayList<>();

        synchronized void add(long ts, double v) {
            if (v < 0 || v > 1) return;
            int n = t.size();
            if (n > 0) {
                long last = t.get(n - 1);
                if (ts < last) return;
                if (ts - last < 1000) { p.set(n - 1, v); return; }
            }
            t.add(ts);
            p.add(v);
        }

        /** Last value at or before ts (NaN if none). */
        synchronized double at(long ts) {
            for (int i = t.size() - 1; i >= 0; i--) if (t.get(i) <= ts) return p.get(i);
            return Double.NaN;
        }

        /** Last value in each [b0 + j*bucket, +bucket) up to t1, carried forward; null if no data. */
        synchronized double[] bucketed(long b0, long t1, long bucket) {
            int n = (int) ((t1 - b0) / bucket) + 1;
            if (n <= 0 || t.isEmpty() || t.get(0) > t1) return null;
            double[] v = new double[n];
            double cur = Double.NaN;
            int i = 0;
            for (int j = 0; j < n; j++) {
                long end = Math.min(b0 + (j + 1) * bucket, t1 + 1);
                while (i < t.size() && t.get(i) < end) cur = p.get(i++);
                v[j] = cur;
            }
            return v;
        }
    }

    // ---------- lifecycle ----------

    static void start(Context c, Intent in) {
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(in); else c.startService(in);
    }

    static Intent cmd(Context c, String action) {
        return new Intent(c, OverlayService.class).setAction(action);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("p", MODE_PRIVATE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        foreground();
        server = new ControlServer(this);
        server.start();
        new Thread(() -> Game.list(0)).start();
    }

    @Override
    public int onStartCommand(Intent in, int flags, int id) {
        String act = in == null || in.getAction() == null ? "start" : in.getAction();
        switch (act) {
            case "show": {
                Game g = Game.byId(in.getStringExtra("id"));
                if (g != null) show(g); else new Thread(() -> { // cold start (adb / boot): load list first
                    Game.list(0);
                    Game gg = Game.byId(in.getStringExtra("id"));
                    if (gg != null) main.post(() -> show(gg));
                }).start();
                break;
            }
            case "hide": hide(); break;
            case "restyle": restyle(); break;
            case "quit": stopSelf(); break;
            default: break; // "start"/"boot": just run server + detection
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        stopFeed();
        if (view != null) wm.removeView(view);
        view = null;
        if (server != null) server.stop();
        super.onDestroy();
    }

    void foreground() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("o", "Overlay", NotificationManager.IMPORTANCE_MIN);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
            b = new Notification.Builder(this, "o");
        } else {
            b = new Notification.Builder(this);
        }
        startForeground(1, b.setContentTitle("Poly Odds").setContentText(ControlServer.url())
                .setSmallIcon(android.R.drawable.ic_menu_view).build());
    }

    // ---------- settings ----------

    int pos() { return prefs.getInt("pos", 1); }
    int size() { return prefs.getInt("size", 1); }
    int delay() { return prefs.getInt("delay", 0); }
    boolean auto() { return prefs.getBoolean("auto", true); }
    boolean visible() { return view != null; }

    void set(String k, int v) {
        prefs.edit().putInt(k, v).apply();
        main.post(this::restyle);
    }

    void setAuto(boolean on) { prefs.edit().putBoolean("auto", on).apply(); }

    static boolean canOverlay(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    // ---------- overlay ----------

    void show(Game g) {
        if (!canOverlay(this)) { Log.w(TAG, "no overlay permission"); return; }
        Log.i(TAG, "show " + g.league + " " + g.title);
        if (view == null) {
            view = new ChartView(this);
            wm.addView(view, params());
        }
        view.delayMs = delay() * 1000L;
        if (g != game || series == null) {
            game = g;
            prefs.edit().putString("game", g.id).apply();
            Series[] s = new Series[g.tokens.length];
            for (int i = 0; i < s.length; i++) s[i] = new Series();
            series = s;
            long now = System.currentTimeMillis();
            long from = g.start < now ? g.start - 15 * 60_000L : now - 6 * 3600_000L;
            view.setGame(g, s, from);
            startFeed(g, s, from);
        }
    }

    void hide() {
        stopFeed();
        game = null;
        series = null;
        if (view != null) wm.removeView(view);
        view = null;
    }

    void restyle() {
        if (view == null) return;
        view.delayMs = delay() * 1000L;
        wm.updateViewLayout(view, params());
    }

    WindowManager.LayoutParams params() {
        int pos = pos(), size = size();
        float d = getResources().getDisplayMetrics().density;
        int w = (int) (new int[]{250, 330, 430}[size] * d), h = (int) (new int[]{112, 145, 190}[size] * d);
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = new int[]{Gravity.BOTTOM | Gravity.LEFT, Gravity.BOTTOM | Gravity.RIGHT,
                Gravity.TOP | Gravity.LEFT, Gravity.TOP | Gravity.RIGHT}[pos];
        p.x = (int) (28 * d);
        p.y = (int) ((pos < 2 ? 70 : 24) * d); // stay clear of bottom scorebugs
        return p;
    }

    // ---------- price feed ----------

    void stopFeed() {
        if (feed != null) feed.interrupt();
        feed = null;
        WsClient w = ws;
        if (w != null) w.close();
    }

    void startFeed(Game g, Series[] s, long from) {
        stopFeed();
        feed = new Thread(() -> runFeed(g, s, from), "feed");
        feed.start();
    }

    private void runFeed(Game g, Series[] s, long from) {
        Thread me = Thread.currentThread();
        long now = System.currentTimeMillis();
        for (int i = 0; i < g.tokens.length; i++) {
            try {
                JSONArray h = new JSONObject(Api.get(Api.CLOB + "/prices-history?market=" + g.tokens[i]
                        + "&startTs=" + from / 1000 + "&endTs=" + now / 1000 + "&fidelity=1")).getJSONArray("history");
                for (int j = 0; j < h.length(); j++) {
                    JSONObject p = h.getJSONObject(j);
                    s[i].add(p.getLong("t") * 1000, p.getDouble("p"));
                }
            } catch (Exception e) {
                Log.w(TAG, "history " + e);
            }
            if (me.isInterrupted()) return;
        }
        poll(g, s); // fill to "now" right away
        while (!me.isInterrupted()) {
            try {
                ws(g, s);
            } catch (Exception e) {
                if (me.isInterrupted()) return;
                Log.w(TAG, "ws " + e + " — polling");
                // websocket down: poll REST for a while, then retry it
                for (int k = 0; k < 8 && !me.isInterrupted(); k++) {
                    poll(g, s);
                    try { Thread.sleep(4000); } catch (InterruptedException ie) { return; }
                }
            }
        }
    }

    private void poll(Game g, Series[] s) {
        for (int i = 0; i < g.tokens.length; i++) {
            try {
                JSONObject m = new JSONObject(Api.get(Api.CLOB + "/midpoint?token_id=" + g.tokens[i]));
                if (m.has("mid")) s[i].add(System.currentTimeMillis(), Double.parseDouble(m.getString("mid")));
            } catch (Exception ignored) {
            }
        }
    }

    private void ws(Game g, Series[] s) throws Exception {
        WsClient w = new WsClient("ws-subscriptions-clob.polymarket.com", "/ws/market");
        ws = w;
        try {
            JSONArray ids = new JSONArray();
            for (String t : g.tokens) ids.put(t);
            w.send(new JSONObject().put("assets_ids", ids).put("type", "market").toString());
            Thread ping = new Thread(() -> {
                try { while (true) { Thread.sleep(10_000); w.send("PING"); } } catch (Exception ignored) { }
            });
            ping.setDaemon(true);
            ping.start();
            Log.i(TAG, "ws subscribed " + g.title);
            w.run(text -> onWs(g, s, text));
        } finally {
            w.close();
            ws = null;
        }
    }

    private void onWs(Game g, Series[] s, String text) {
        if (!text.startsWith("{") && !text.startsWith("[")) return; // PONG
        try {
            long now = System.currentTimeMillis();
            if (text.startsWith("[")) { // initial book snapshots
                JSONArray a = new JSONArray(text);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject b = a.getJSONObject(i);
                    double bid = best(b.optJSONArray("bids"), true), ask = best(b.optJSONArray("asks"), false);
                    put(g, s, b.optString("asset_id"), bid, ask, now);
                }
                return;
            }
            JSONObject o = new JSONObject(text);
            JSONArray pc = o.optJSONArray("price_changes");
            if (pc != null) for (int i = 0; i < pc.length(); i++) {
                JSONObject c = pc.getJSONObject(i);
                put(g, s, c.optString("asset_id"), c.optDouble("best_bid", Double.NaN), c.optDouble("best_ask", Double.NaN), now);
            }
            else if (o.has("bids")) put(g, s, o.optString("asset_id"), best(o.optJSONArray("bids"), true), best(o.optJSONArray("asks"), false), now);
        } catch (Exception e) {
            Log.w(TAG, "ws parse " + e);
        }
    }

    private static double best(JSONArray lv, boolean bid) {
        if (lv == null) return Double.NaN;
        double b = Double.NaN;
        for (int i = 0; i < lv.length(); i++) {
            double p = lv.optJSONObject(i).optDouble("price", Double.NaN);
            if (Double.isNaN(b) || (bid ? p > b : p < b)) b = p;
        }
        return b;
    }

    private static void put(Game g, Series[] s, String asset, double bid, double ask, long now) {
        if (Double.isNaN(bid) || Double.isNaN(ask) || ask - bid > 0.10) return; // Polymarket hides mids on wide books too
        for (int i = 0; i < g.tokens.length; i++) if (g.tokens[i].equals(asset)) s[i].add(now, (bid + ask) / 2);
    }

    // ---------- auto-detect ----------

    /** Called by DetectService with every text node visible in the foreground app. */
    void onScreen(String pkg, List<String> texts) {
        if (!auto() || getPackageName().equals(pkg)) return;
        long now = System.currentTimeMillis();
        List<Game> games = Game.cached();
        if (now - lastDetect > 5 * 60_000L) { lastDetect = now; new Thread(() -> Game.list(5 * 60_000L)).start(); }
        List<Game> hits = new ArrayList<>();
        for (String t : texts) {
            String n = Game.norm(t);
            if (n.length() < 5) continue;
            for (Game g : games)
                if (!hits.contains(g) && (g.live || g.start - now < 60 * 60_000L) && g.namedIn(n)) hits.add(g);
        }
        if (hits.isEmpty()) return;
        // the guide/EPG lists many games at once: only act when the screen names one (or one live one)
        Game pick = null;
        if (hits.size() == 1) pick = hits.get(0);
        else {
            for (Game g : hits) if (g.live) { if (pick != null) { pick = null; break; } pick = g; }
        }
        detectNote = pkg + ": " + hits.size() + " match" + (pick != null ? " → " + pick.title : " (ambiguous)");
        Log.i(TAG, "detect " + detectNote);
        if (pick != null && pick != game) {
            Game p = pick;
            main.post(() -> show(p));
        }
    }

    static boolean isFireTv(Context c) {
        return c.getPackageManager().hasSystemFeature("amazon.hardware.fire_tv");
    }

    static boolean detectEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(c.getPackageName() + "/");
    }

    // ---------- state for the phone page ----------

    JSONObject state() throws Exception {
        JSONObject o = new JSONObject()
                .put("visible", visible()).put("delay", delay()).put("pos", pos()).put("size", size())
                .put("auto", auto()).put("detect", detectEnabled(this)).put("note", detectNote)
                .put("overlayOk", canOverlay(this)).put("fire", isFireTv(this));
        Game g = game;
        Series[] s = series;
        if (g != null && s != null) {
            JSONArray px = new JSONArray();
            long cut = System.currentTimeMillis() - delay() * 1000L;
            for (Series x : s) { double v = x.at(cut); px.put(Double.isNaN(v) ? JSONObject.NULL : v); }
            o.put("game", gameJson(g).put("now", px));
        }
        return o;
    }

    static JSONObject gameJson(Game g) throws Exception {
        JSONArray n = new JSONArray(), c = new JSONArray(), p = new JSONArray();
        for (int i = 0; i < g.names.length; i++) {
            n.put(g.shorts[i]);
            c.put(String.format("#%06X", 0xFFFFFF & g.colors[i]));
            p.put(g.px[i]);
        }
        return new JSONObject().put("id", g.id).put("title", g.title).put("league", g.league)
                .put("live", g.live).put("start", g.start).put("names", n).put("colors", c).put("px", p);
    }
}
