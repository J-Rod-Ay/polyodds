package app.polyodds;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** TV menu: pick a game with the remote, plus settings. The phone page (ControlServer) does the same from the couch. */
public class MainActivity extends Activity {
    static final String[] POSITIONS = {"Bottom left", "Bottom right", "Top left", "Top right"};
    static final String[] SIZES = {"Small", "Medium", "Large"};

    static final class Item {
        final String label;
        final Runnable action;
        Item(String label, Runnable action) { this.label = label; this.action = action; }
        @Override public String toString() { return label; }
    }

    SharedPreferences prefs;
    ArrayAdapter<Item> adapter;
    TextView status;
    final List<Item> games = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("p", MODE_PRIVATE);
        OverlayService.start(this, OverlayService.cmd(this, "start"));

        // adb/automation: am start -n app.polyodds/.MainActivity --es id <gamma event id>
        if (getIntent().hasExtra("id")) { pickId(getIntent().getStringExtra("id")); return; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(64, 40, 64, 40);
        root.setBackgroundColor(Color.rgb(16, 18, 24));

        TextView title = new TextView(this);
        title.setText("Poly Odds");
        title.setTextSize(26);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(Color.rgb(150, 160, 180));
        status.setPadding(0, 8, 0, 16);
        root.addView(status);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<Item>(this, android.R.layout.simple_list_item_1) {
            @Override public View getView(int pos, View v, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, v, parent);
                t.setTextColor(Color.WHITE);
                t.setTextSize(19);
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setSelector(new ColorDrawable(Color.rgb(40, 90, 200))); // Fire OS default focus highlight is invisible here
        list.setOnItemClickListener((p, v, pos, id) -> adapter.getItem(pos).action.run());
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        rebuild();
        list.requestFocus();
        load();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.hasExtra("id")) pickId(i.getStringExtra("id"));
    }

    void pickId(String id) {
        OverlayService.start(this, OverlayService.cmd(this, "show").putExtra("id", id));
        backToTv();
    }

    void rebuild() {
        if (adapter == null) return;
        OverlayService svc = OverlayService.instance;
        boolean detect = OverlayService.detectEnabled(this);
        boolean auto = prefs.getBoolean("auto", true);
        status.setText("Phone remote: " + ControlServer.url() + "     ·     auto-detect "
                + (!detect ? (OverlayService.isFireTv(this) ? "n/a on Fire TV" : "off (enable accessibility)") : auto ? "on" : "off"));
        adapter.clear();
        if (svc != null && svc.visible()) adapter.add(new Item("■  Hide overlay", () -> {
            OverlayService.start(this, OverlayService.cmd(this, "hide"));
            status.postDelayed(this::rebuild, 200);
        }));
        if (detect) adapter.add(new Item("⚙  Auto-detect game: " + (auto ? "On" : "Off"), () -> {
            prefs.edit().putBoolean("auto", !auto).apply();
            rebuild();
        }));
        int delay = prefs.getInt("delay", 0);
        adapter.add(new Item("⚙  Broadcast delay: " + delay + "s", () -> {
            int next = 0;
            for (int d : OverlayService.DELAYS) if (d > delay) { next = d; break; }
            setPref("delay", next);
        }));
        int pos = prefs.getInt("pos", 1), size = prefs.getInt("size", 1);
        adapter.add(new Item("⚙  Position: " + POSITIONS[pos], () -> setPref("pos", (pos + 1) % POSITIONS.length)));
        adapter.add(new Item("⚙  Size: " + SIZES[size], () -> setPref("size", (size + 1) % SIZES.length)));
        adapter.addAll(games);
    }

    void setPref(String k, int v) {
        prefs.edit().putInt(k, v).apply();
        OverlayService.start(this, OverlayService.cmd(this, "restyle"));
        rebuild();
    }

    void load() {
        status.setText("Loading games…");
        new Thread(() -> {
            List<Game> gs = Game.list(60_000);
            SimpleDateFormat when = new SimpleDateFormat("EEE h:mm a", Locale.US);
            List<Item> items = new ArrayList<>();
            for (Game g : gs) {
                StringBuilder px = new StringBuilder();
                for (double p : g.px) px.append(px.length() > 0 ? " / " : "").append(Math.round(p * 100)).append('%');
                String label = (g.live ? "●  LIVE  " : "    " + when.format(new Date(g.start)) + "  ")
                        + g.league + "  " + g.title + "    " + px;
                items.add(new Item(label, () -> show(g)));
            }
            runOnUiThread(() -> {
                games.clear();
                games.addAll(items);
                rebuild();
                if (items.isEmpty() && Game.lastError != null) status.setText("⚠ " + Game.lastError);
            });
        }).start();
    }

    void show(Game g) {
        if (!OverlayService.canOverlay(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Overlay permission needed")
                    .setMessage("Fire TV hides this setting. From a computer run:\n\n"
                            + "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow")
                    .setPositiveButton("OK", null).show();
            return;
        }
        pickId(g.id);
    }

    void backToTv() {
        // jump straight back into TiviMate if it's installed
        Intent tivi = getPackageManager().getLaunchIntentForPackage("ar.tvplayer.tv");
        if (tivi != null) startActivity(tivi);
        finish();
    }

    @Override protected void onResume() {
        super.onResume();
        rebuild();
    }
}
