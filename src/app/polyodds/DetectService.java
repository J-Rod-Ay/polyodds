package app.polyodds;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/** Reads the visible text of the foreground app (e.g. TiviMate's channel banner) so the overlay can follow the game. */
public class DetectService extends AccessibilityService {
    long last, logged;

    @Override
    protected void onServiceConnected() {
        // the system (re)binds us at boot, so this also keeps the phone remote alive after a reboot
        try { OverlayService.start(this, OverlayService.cmd(this, "start")); } catch (Exception ignored) { }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        long now = System.currentTimeMillis();
        if (now - last < 1000) return;
        last = now;
        OverlayService svc = OverlayService.instance;
        if (svc == null) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        List<String> texts = new ArrayList<>();
        collect(root, texts, 0);
        CharSequence pkg = root.getPackageName();
        root.recycle();
        if (now - logged > 15_000) {
            logged = now;
            android.util.Log.d(OverlayService.TAG, "a11y " + pkg + " " + texts.size() + " texts");
        }
        if (!texts.isEmpty()) svc.onScreen(pkg == null ? "" : pkg.toString(), texts);
    }

    private static void collect(AccessibilityNodeInfo n, List<String> out, int depth) {
        if (n == null || out.size() > 400 || depth > 30) return;
        if (n.getText() != null) out.add(n.getText().toString());
        if (n.getContentDescription() != null) out.add(n.getContentDescription().toString());
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            collect(c, out, depth + 1);
            if (c != null) c.recycle();
        }
    }

    @Override public void onInterrupt() { }
}
