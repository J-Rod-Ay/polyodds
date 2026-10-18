package app.polyodds;

import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;

/** Phone remote on http://<tv-ip>:8765 — pick games, delay, position, size, auto-detect. LAN only, no auth. */
final class ControlServer {
    static final int PORT = 8765;
    final OverlayService svc;
    ServerSocket ss;
    byte[] page;

    ControlServer(OverlayService svc) { this.svc = svc; }

    static String url() {
        String ip = ip();
        return ip == null ? "phone remote: no network" : "http://" + ip + ":" + PORT;
    }

    static String ip() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) { }
        return null;
    }

    void start() {
        new Thread(() -> {
            try {
                ss = new ServerSocket(PORT);
                Log.i(OverlayService.TAG, "control page at " + url());
                while (true) {
                    Socket s = ss.accept();
                    new Thread(() -> handle(s)).start();
                }
            } catch (Exception e) {
                Log.w(OverlayService.TAG, "control server: " + e);
            }
        }, "http").start();
    }

    void stop() {
        try { if (ss != null) ss.close(); } catch (Exception ignored) { }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10000);
            BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"));
            String req = r.readLine();
            if (req == null) return;
            while (true) { String h = r.readLine(); if (h == null || h.isEmpty()) break; }
            String[] parts = req.split(" ");
            Uri u = Uri.parse("http://x" + (parts.length > 1 ? parts[1] : "/"));
            String path = u.getPath() == null ? "/" : u.getPath();
            OutputStream out = sock.getOutputStream();
            switch (path) {
                case "/": send(out, "text/html; charset=utf-8", page()); return;
                case "/api/state": json(out, svc.state()); return;
                case "/api/games": {
                    JSONArray a = new JSONArray();
                    for (Game g : Game.list(2 * 60_000L)) a.put(OverlayService.gameJson(g));
                    json(out, new JSONObject().put("games", a).put("error", Game.lastError == null ? "" : Game.lastError));
                    return;
                }
                case "/api/pick": {
                    Game g = Game.byId(u.getQueryParameter("id"));
                    if (g != null) svc.main.post(() -> svc.show(g));
                    json(out, new JSONObject().put("ok", g != null));
                    return;
                }
                case "/api/detect": { // debug: run the matcher on text as if it were on screen; '|' separates nodes
                    String t = u.getQueryParameter("text");
                    if (t != null) svc.onScreen("debug", java.util.Arrays.asList(t.split("\\|")));
                    json(out, new JSONObject().put("note", svc.detectNote));
                    return;
                }
                case "/api/hide": svc.main.post(svc::hide); json(out, new JSONObject().put("ok", true)); return;
                case "/api/set": {
                    for (String k : new String[]{"delay", "pos", "size"}) {
                        String v = u.getQueryParameter(k);
                        if (v != null) svc.set(k, Math.max(0, Math.min(k.equals("delay") ? 300 : 3, Integer.parseInt(v))));
                    }
                    String a = u.getQueryParameter("auto");
                    if (a != null) svc.setAuto(a.equals("1"));
                    json(out, svc.state());
                    return;
                }
                default: send(out, "text/plain", "not found".getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.w(OverlayService.TAG, "http " + e);
        }
    }

    private byte[] page() throws Exception {
        if (page == null) try (InputStream in = svc.getAssets().open("control.html")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            page = b.toByteArray();
        }
        return page;
    }

    private static void json(OutputStream out, JSONObject o) throws Exception {
        send(out, "application/json", o.toString().getBytes("UTF-8"));
    }

    private static void send(OutputStream out, String type, byte[] body) throws Exception {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }
}
