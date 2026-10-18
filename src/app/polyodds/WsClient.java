package app.polyodds;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Just enough RFC 6455 for Polymarket's market channel: TLS, masked client text frames, ping/pong, fragments. */
final class WsClient {
    interface Listener { void onText(String s); }

    private final SSLSocket sock;
    private final DataInputStream in;
    private final OutputStream out;
    private final SecureRandom rnd = new SecureRandom();

    WsClient(String host, String path) throws IOException {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, 443), 8000);
        SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, 443, true); // SNI
        s.setSoTimeout(30000);
        s.startHandshake();
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession())) {
            s.close();
            throw new IOException("bad cert for " + host);
        }
        sock = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();

        byte[] key = new byte[16];
        rnd.nextBytes(key);
        String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
                + "\r\nSec-WebSocket-Version: 13\r\nUser-Agent: PolyOdds\r\n\r\n";
        out.write(req.getBytes("UTF-8"));
        out.flush();
        String status = line();
        if (!status.contains(" 101")) throw new IOException("ws handshake: " + status);
        while (!line().isEmpty()) { }
    }

    private String line() throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = in.read()) != '\n'; ) {
            if (c < 0) throw new IOException("eof");
            if (c != '\r') b.append((char) c);
        }
        return b.toString();
    }

    synchronized void send(String text) throws IOException { frame(0x1, text.getBytes("UTF-8")); }

    private synchronized void frame(int op, byte[] p) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream(p.length + 14);
        f.write(0x80 | op);
        if (p.length < 126) f.write(0x80 | p.length);
        else if (p.length < 65536) { f.write(0x80 | 126); f.write(p.length >> 8); f.write(p.length); }
        else { f.write(0x80 | 127); for (int i = 7; i >= 0; i--) f.write((int) ((long) p.length >> (8 * i))); }
        byte[] mask = new byte[4];
        rnd.nextBytes(mask);
        f.write(mask);
        for (int i = 0; i < p.length; i++) f.write(p[i] ^ mask[i & 3]);
        out.write(f.toByteArray());
        out.flush();
    }

    /** Blocks, delivering text messages until the socket dies. */
    void run(Listener l) throws IOException {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        while (true) {
            int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
            int op = b0 & 0x0f;
            long len = b1 & 0x7f;
            if (len == 126) len = in.readUnsignedShort();
            else if (len == 127) len = in.readLong();
            byte[] p = new byte[(int) len];
            in.readFully(p);
            if (op == 0x8) throw new IOException("ws closed by server");
            if (op == 0x9) { frame(0xA, p); continue; }
            if (op == 0xA) continue;
            msg.write(p);
            if ((b0 & 0x80) != 0) {
                l.onText(msg.toString("UTF-8"));
                msg.reset();
            }
        }
    }

    void close() {
        try { sock.close(); } catch (IOException ignored) { }
    }
}
