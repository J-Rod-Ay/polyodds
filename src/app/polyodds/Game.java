package app.polyodds;

import android.graphics.Color;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** One game with 2 (moneyline) or 3 (soccer: home/away/draw) outcomes, each with its own CLOB token. */
final class Game {
    static final String[][] LEAGUES = {
            {"NFL", "12185"}, {"CFB", "12756"}, {"NBA", "10345"}, {"MLB", "3"}, {"NHL", "10346"},
            {"CBB", "10470"}, {"WNBA", "10105"}, {"EPL", "10188"}, {"UCL", "10204"}, {"MLS", "10189"},
            {"LIGA", "10193"}, {"BUN", "10194"}, {"SERA", "10203"}, {"L1", "10195"},
    };
    static final int DRAW_COLOR = Color.rgb(170, 170, 178);

    String id, title, league, slug;
    long start;
    boolean live;
    String[] names, shorts, tokens;
    int[] colors;
    double[] px;
    /** lowercase word-bounded patterns per outcome (full name, nickname, city) for on-screen matching */
    Pattern[][] keys;

    // ---------- fetching ----------

    private static List<Game> cache = new ArrayList<>();
    private static long cachedAt;

    static synchronized List<Game> cached() { return cache; }

    /** All live + next-48h games, newest list at most maxAgeMs old. */
    static List<Game> list(long maxAgeMs) {
        synchronized (Game.class) {
            if (System.currentTimeMillis() - cachedAt < maxAgeMs) return cache;
        }
        List<Game> fresh = fetch();
        synchronized (Game.class) {
            if (!fresh.isEmpty() || cache.isEmpty()) { cache = fresh; cachedAt = System.currentTimeMillis(); }
            return cache;
        }
    }

    static Game byId(String id) {
        for (Game g : cached()) if (g.id.equals(id)) return g;
        return null;
    }

    static String lastError;

    private static List<Game> fetch() {
        final List<Game> out = Collections.synchronizedList(new ArrayList<Game>());
        final long now = System.currentTimeMillis();
        final SimpleDateFormat iso = isoFmt();
        final String min = iso.format(new Date(now - 5 * 3600_000L)), max = iso.format(new Date(now + 48 * 3600_000L));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        lastError = null;
        for (final String[] lg : LEAGUES) pool.execute(() -> {
            // NFL events carry hundreds of prop markets (~15MB/page), so stream-parse instead of loading it all
            try (JsonReader r = new JsonReader(new InputStreamReader(Api.open(Api.GAMMA + "/events?series_id=" + lg[1]
                    + "&closed=false&limit=40&order=startTime&ascending=true&start_time_min=" + min
                    + "&start_time_max=" + max), "UTF-8"))) {
                r.beginArray();
                while (r.hasNext()) {
                    Game g = parse(r, lg[0], isoFmt(), now);
                    if (g != null) out.add(g);
                }
                r.endArray();
            } catch (Exception e) {
                lastError = lg[0] + ": " + e;
            }
        });
        pool.shutdown();
        try { pool.awaitTermination(60, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        List<Game> sorted = new ArrayList<>(out);
        Collections.sort(sorted, (a, b) -> a.live != b.live ? (a.live ? -1 : 1) : Long.compare(a.start, b.start));
        return sorted;
    }

    static SimpleDateFormat isoFmt() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    /** Reads one event; keeps only what we need and skips the (huge) rest. */
    private static Game parse(JsonReader r, String league, SimpleDateFormat iso, long now) throws Exception {
        JSONObject e = new JSONObject();
        List<JSONObject> mls = new ArrayList<>();
        List<JSONObject> teams = new ArrayList<>();
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if (r.peek() == JsonToken.NULL) { r.skipValue(); continue; }
            switch (k) {
                case "id": case "title": case "startTime": case "slug": e.put(k, r.nextString()); break;
                case "live": case "ended": e.put(k, r.nextBoolean()); break;
                case "teams": teams = readObjects(r, "name", "alias", "color"); break;
                case "markets":
                    for (JSONObject m : readObjects(r, "sportsMarketType", "outcomes", "clobTokenIds", "outcomePrices", "groupItemTitle"))
                        if ("moneyline".equals(m.optString("sportsMarketType"))) mls.add(m);
                    break;
                default: r.skipValue();
            }
        }
        r.endObject();
        if (e.optBoolean("ended") || !e.has("startTime") || mls.isEmpty()) return null;

        Game g = new Game();
        g.id = e.getString("id");
        g.title = e.getString("title");
        g.slug = e.optString("slug");
        g.league = league;
        g.start = iso.parse(e.getString("startTime")).getTime();
        g.live = e.optBoolean("live") || g.start < now;

        if (mls.size() == 1) { // US-style: one market, outcomes = the two teams
            JSONObject m = mls.get(0);
            JSONArray outs = new JSONArray(m.getString("outcomes")), toks = new JSONArray(m.getString("clobTokenIds"));
            JSONArray p = new JSONArray(m.optString("outcomePrices", "[\"0.5\",\"0.5\"]"));
            if (outs.length() != 2) return null;
            g.names = new String[]{outs.getString(0), outs.getString(1)};
            g.tokens = new String[]{toks.getString(0), toks.getString(1)};
            g.px = new double[]{p.optDouble(0, .5), p.optDouble(1, .5)};
        } else if (mls.size() == 3) { // soccer: "Will X win?" x2 + draw, each a Yes/No market
            String[] n = new String[3], t = new String[3];
            double[] p = new double[3];
            int team = 0;
            for (JSONObject m : mls) {
                String git = m.optString("groupItemTitle");
                int i = git.startsWith("Draw") ? 2 : team++;
                if (i > 2) return null;
                n[i] = i == 2 ? "Draw" : git;
                t[i] = new JSONArray(m.getString("clobTokenIds")).getString(0);
                p[i] = new JSONArray(m.optString("outcomePrices", "[\"0.33\"]")).optDouble(0, .33);
            }
            if (team != 2) return null;
            g.names = n; g.tokens = t; g.px = p;
        } else return null;

        int k = g.names.length;
        g.shorts = new String[k];
        g.colors = new int[k];
        g.keys = new Pattern[k][];
        for (int i = 0; i < k; i++) {
            JSONObject tm = team(teams, g.names[i]);
            String alias = tm == null ? "" : tm.optString("alias");
            g.shorts[i] = shortName(g.names[i], alias);
            g.colors[i] = i == 2 ? DRAW_COLOR : vivid(tm == null ? null : tm.optString("color", null), i);
            g.keys[i] = i == 2 ? new Pattern[0] : keys(tm == null ? g.names[i] : tm.optString("name", g.names[i]), alias);
        }
        // two teams with near-identical colors: second one goes light grey
        if (k >= 2 && close(g.colors[0], g.colors[1])) g.colors[1] = Color.rgb(225, 225, 230);
        return g;
    }

    private static List<JSONObject> readObjects(JsonReader r, String... want) throws Exception {
        List<JSONObject> list = new ArrayList<>();
        r.beginArray();
        while (r.hasNext()) {
            JSONObject o = new JSONObject();
            r.beginObject();
            while (r.hasNext()) {
                String k = r.nextName();
                boolean keep = false;
                for (String w : want) keep |= w.equals(k);
                if (keep && r.peek() == JsonToken.STRING) o.put(k, r.nextString()); else r.skipValue();
            }
            r.endObject();
            list.add(o);
        }
        r.endArray();
        return list;
    }

    private static JSONObject team(List<JSONObject> teams, String name) {
        for (JSONObject t : teams)
            if (name.equalsIgnoreCase(t.optString("name")) || name.equalsIgnoreCase(t.optString("alias"))) return t;
        for (JSONObject t : teams) { // "Tampa Bay Rays" vs outcome "Rays", or the reverse
            String n = t.optString("name").toLowerCase(Locale.US), o = name.toLowerCase(Locale.US);
            if (!n.isEmpty() && (n.contains(o) || o.contains(n))) return t;
        }
        return null;
    }

    static String shortName(String name, String alias) {
        String s = name.replaceAll("\\s+(FC|CF|SC|AFC)$", "").replaceAll("^(FC|AFC|CF)\\s+", "");
        if (s.length() <= 12) return s;
        if (!alias.isEmpty() && alias.length() <= 12) return alias;
        // no nickname (mostly soccer): "Coventry City" -> "Coventry", "Rayo Vallecano de Madrid" -> "Rayo Vallecano"
        String[] w = s.split(" ");
        String out = w[0];
        if (out.length() < 5 && w.length > 1 && (out + " " + w[1]).length() <= 14) out += " " + w[1];
        return out.length() > 12 ? out.substring(0, 12) : out;
    }

    // ---------- colors ----------

    static int vivid(String hex, int i) {
        int c;
        try { c = Color.parseColor(hex); } catch (Exception ex) { c = i == 0 ? Color.rgb(80, 170, 255) : Color.rgb(255, 160, 60); }
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[2] = Math.max(hsv[2], 0.85f);           // readable over video
        if (hsv[1] < 0.15f) hsv[2] = Math.max(hsv[2], 0.9f);
        return Color.HSVToColor(hsv);
    }

    static boolean close(int a, int b) {
        int dr = Color.red(a) - Color.red(b), dg = Color.green(a) - Color.green(b), db = Color.blue(a) - Color.blue(b);
        return dr * dr + dg * dg + db * db < 70 * 70;
    }

    // ---------- on-screen matching ----------

    static String norm(String s) {
        return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static Pattern[] keys(String name, String alias) {
        List<String> ks = new ArrayList<>();
        String n = norm(name.replaceAll("\\s+(FC|CF|SC|AFC)$", ""));
        ks.add(n);
        String a = norm(alias);
        if (a.length() >= 4) ks.add(a);
        if (!a.isEmpty() && n.endsWith(" " + a)) {
            String city = n.substring(0, n.length() - a.length() - 1);
            if (city.length() >= 4) ks.add(city);
        }
        String[] w = n.split(" "); // "Ohio State Buckeyes" style: nickname last
        if (w.length > 1 && w[w.length - 1].length() >= 5) ks.add(w[w.length - 1]);
        Pattern[] ps = new Pattern[ks.size()];
        for (int i = 0; i < ps.length; i++) ps[i] = Pattern.compile("\\b" + Pattern.quote(ks.get(i)) + "\\b");
        return ps;
    }

    private static boolean has(String text, Pattern[] ps) {
        for (Pattern p : ps) if (p.matcher(text).find()) return true;
        return false;
    }

    /** True if both teams are named in this (normalized) piece of text. */
    boolean namedIn(String normText) {
        return has(normText, keys[0]) && has(normText, keys[1]);
    }
}
