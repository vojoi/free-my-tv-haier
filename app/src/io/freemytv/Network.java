package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.*;

/** API-only DNS fallback. TLS still validates the original host with Android's trust store. */
final class Network {
    private Network() {}
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "api.themoviedb.org", "image.tmdb.org", "api.open-meteo.com", "geocoding-api.open-meteo.com"));
    private static final Map<String, Endpoint> WORKING = new ConcurrentHashMap<>();
    private static final Map<String, Answers> DNS = new ConcurrentHashMap<>();
    private static final long MAX_BODY = 64L * 1024 * 1024;
    private static final class Endpoint {
        final String ip; final long expires;
        Endpoint(String ip) { this.ip = ip; expires = System.currentTimeMillis() + 300_000; }
    }
    private static final class Answers {
        final List<String> ips; final long expires;
        Answers(List<String> ips, long ttl) { this.ips = ips; expires = System.currentTimeMillis() + ttl; }
    }

    static HttpURLConnection open(URL url) throws IOException {
        if (!"https".equals(url.getProtocol())) throw new IOException("HTTPS required");
        return HOSTS.contains(url.getHost()) ? new Connection(url) : (HttpURLConnection) url.openConnection();
    }

    private static List<String> resolve(String host, boolean otherRegion) throws IOException {
        String cacheKey = host + ":" + otherRegion;
        Answers saved = DNS.get(cacheKey);
        if (saved != null && saved.expires > System.currentTimeMillis()) return saved.ips;
        // The alternate region selects a different API edge, not the forecast's location.
        String subnet = otherRegion ? "8.8.8.0/24" : "0.0.0.0/0";
        URL url = new URL("https://dns.google/resolve?type=A&name=" + host + "&edns_client_subnet=" + subnet);
        HttpsURLConnection c = (HttpsURLConnection) url.openConnection();
        c.setConnectTimeout(4000); c.setReadTimeout(4000); c.setInstanceFollowRedirects(false);
        try {
            if (c.getResponseCode() != 200) throw new IOException("DNS service unavailable");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] bytes = new byte[4096]; int n;
                while ((n = in.read(bytes)) != -1) {
                    if (out.size() + n > 65536) throw new IOException("DNS answer too large");
                    out.write(bytes, 0, n);
                }
            }
            JSONObject json = new JSONObject(out.toString("UTF-8"));
            if (json.optInt("Status", -1) != 0) throw new IOException("DNS lookup failed");
            JSONArray answer = json.optJSONArray("Answer");
            List<String> ips = new ArrayList<>(); long ttl = 600;
            for (int i = 0; answer != null && i < answer.length(); i++) {
                JSONObject item = answer.getJSONObject(i);
                String ip = item.optString("data");
                if (item.optInt("type") == 1 && publicIpv4(ip) && !ips.contains(ip)) {
                    ips.add(ip); ttl = Math.min(ttl, Math.max(30, item.optLong("TTL", 60)));
                }
            }
            if (ips.isEmpty()) throw new IOException("No public DNS addresses");
            DNS.put(cacheKey, new Answers(ips, ttl * 1000));
            return ips;
        } catch (org.json.JSONException e) { throw new IOException("Invalid DNS answer"); }
        finally { c.disconnect(); }
    }

    private static boolean publicIpv4(String text) {
        if (!text.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] parts = text.split("\\."); int a = Integer.parseInt(parts[0]), b = Integer.parseInt(parts[1]);
        for (String part : parts) if (Integer.parseInt(part) > 255) return false;
        return a > 0 && a < 224 && a != 10 && a != 127 && !(a == 169 && b == 254)
                && !(a == 172 && b >= 16 && b <= 31) && !(a == 192 && b == 168)
                && !(a == 100 && b >= 64 && b <= 127);
    }

    private static final class Connection extends HttpURLConnection {
        private HttpURLConnection direct;
        private SSLSocket socket;
        private InputStream body;
        Connection(URL url) { super(url); }
        @Override public void connect() throws IOException {
            if (connected) return;
            String host = url.getHost();
            if (url.getPort() != -1 && url.getPort() != 443) throw new IOException("Unexpected API port");
            Endpoint saved = WORKING.get(host);
            Set<String> tried = new HashSet<>();
            if (saved != null && saved.expires > System.currentTimeMillis()) {
                try { connectTo(saved.ip); return; }
                catch (IOException e) { closeSocket(); tried.add(saved.ip); WORKING.remove(host, saved); }
            }
            try {
                direct = (HttpURLConnection) url.openConnection();
                direct.setConnectTimeout(getConnectTimeout()); direct.setReadTimeout(getReadTimeout());
                direct.setInstanceFollowRedirects(false);
                for (Map.Entry<String,List<String>> h : getRequestProperties().entrySet())
                    for (String value : h.getValue()) direct.addRequestProperty(h.getKey(), value);
                responseCode = direct.getResponseCode(); connected = true; return;
            } catch (IOException e) {
                if (direct != null) direct.disconnect(); direct = null;
                try { for (InetAddress ip : InetAddress.getAllByName(host)) tried.add(ip.getHostAddress()); }
                catch (UnknownHostException ignored) {}
            }
            for (boolean other : new boolean[] {false, true}) {
                List<String> ips;
                try { ips = resolve(host, other); } catch (IOException e) { continue; }
                for (String ip : ips) {
                    if (!tried.add(ip)) continue;
                    try {
                        connectTo(ip);
                        WORKING.put(host, new Endpoint(ip));
                        AppLog.w("API DNS fallback active for " + host);
                        return;
                    } catch (IOException e) { closeSocket(); }
                }
            }
            throw new IOException("API connection unavailable: " + host);
        }
        private void connectTo(String ip) throws IOException {
            Socket plain = new Socket();
            try {
                plain.connect(new InetSocketAddress(ip, 443), Math.min(4000, getConnectTimeout() > 0 ? getConnectTimeout() : 4000));
                socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, url.getHost(), 443, true);
                socket.setSoTimeout(getReadTimeout() > 0 ? getReadTimeout() : 8000);
                SSLParameters params = socket.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(params); socket.startHandshake();
                String path = url.getFile().isEmpty() ? "/" : url.getFile();
                if (path.contains("\r") || path.contains("\n")) throw new IOException("Invalid API path");
                StringBuilder request = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\nHost: ")
                        .append(url.getHost()).append("\r\nConnection: close\r\nAccept-Encoding: identity\r\n");
                for (Map.Entry<String,List<String>> h : getRequestProperties().entrySet()) {
                    if (!h.getKey().equalsIgnoreCase("Authorization") && !h.getKey().equalsIgnoreCase("Accept")
                            && !h.getKey().equalsIgnoreCase("User-Agent")) continue;
                    for (String value : h.getValue()) {
                        if (value.contains("\r") || value.contains("\n")) throw new IOException("Invalid request header");
                        request.append(h.getKey()).append(": ").append(value).append("\r\n");
                    }
                }
                socket.getOutputStream().write(request.append("\r\n").toString().getBytes(StandardCharsets.UTF_8));
                BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                String status = line(in);
                if (!status.matches("HTTP/1\\.[01] [0-9]{3}.*")) throw new IOException("Invalid HTTP response");
                responseCode = Integer.parseInt(status.substring(9, 12));
                boolean chunked = false, gzip = false; long length = -1; int total = status.length();
                for (String header; !(header = line(in)).isEmpty();) {
                    total += header.length(); if (total > 32768) throw new IOException("HTTP headers too large");
                    int colon = header.indexOf(':'); if (colon < 1) throw new IOException("Invalid HTTP header");
                    String name = header.substring(0, colon).toLowerCase(Locale.ROOT), value = header.substring(colon + 1).trim();
                    if (name.equals("transfer-encoding")) chunked = value.equalsIgnoreCase("chunked");
                    if (name.equals("content-encoding")) gzip = value.equalsIgnoreCase("gzip");
                    if (name.equals("content-length")) {
                        try { length = Long.parseLong(value); } catch (NumberFormatException e) { throw new IOException("Invalid content length"); }
                        if (length < 0 || length > MAX_BODY) throw new IOException("HTTP body too large");
                    }
                }
                body = chunked ? new Chunks(in) : new Limited(in, length >= 0 ? length : MAX_BODY, length >= 0);
                if (gzip) body = new GZIPInputStream(body);
                body = new Limited(body, MAX_BODY, false);
                connected = true;
            } catch (IOException | RuntimeException e) { try { plain.close(); } catch (IOException ignored) {} throw e; }
        }
        @Override public int getResponseCode() throws IOException { connect(); return responseCode; }
        @Override public InputStream getInputStream() throws IOException {
            connect();
            if (responseCode != 200) throw new IOException("HTTP " + responseCode);
            return direct != null ? new Limited(direct.getInputStream(), MAX_BODY, false) : body;
        }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() {
            if (direct != null) direct.disconnect(); closeSocket(); connected = false;
        }
        private void closeSocket() { if (socket != null) try { socket.close(); } catch (IOException ignored) {} socket = null; }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int b; (b = in.read()) != -1;) {
            if (b == '\n') {
                byte[] bytes = out.toByteArray();
                if (bytes.length == 0 || bytes[bytes.length-1] != '\r') throw new IOException("Invalid HTTP line ending");
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            if (out.size() >= 8192) throw new IOException("HTTP line too long"); out.write(b);
        }
        throw new EOFException("Incomplete HTTP headers");
    }
    private static final class Limited extends FilterInputStream {
        long left; final boolean exact;
        Limited(InputStream in, long left, boolean exact) { super(in); this.left = left; this.exact = exact; }
        @Override public int read() throws IOException { byte[] b = new byte[1]; int n = read(b,0,1); return n == -1 ? -1 : b[0] & 255; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (left == 0) {
                if (!exact && in.read() != -1) throw new IOException("HTTP body too large"); return -1;
            }
            int n = in.read(b,off,(int)Math.min(left,len));
            if (n == -1 && exact) throw new EOFException("Incomplete HTTP body");
            if (n > 0) left -= n; return n;
        }
    }
    private static final class Chunks extends FilterInputStream {
        long left, total; boolean separator, done;
        Chunks(InputStream in) { super(in); }
        @Override public int read() throws IOException { byte[] b = new byte[1]; int n = read(b,0,1); return n == -1 ? -1 : b[0] & 255; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0; if (done) return -1;
            if (left == 0) {
                if (separator && (in.read() != '\r' || in.read() != '\n')) throw new IOException("Invalid chunk separator");
                String size = line(in).split(";",2)[0].trim();
                try { left = Long.parseLong(size,16); } catch (NumberFormatException e) { throw new IOException("Invalid chunk size"); }
                if (left < 0 || left > MAX_BODY - total) throw new IOException("HTTP body too large");
                if (left == 0) { done = true; return -1; }
                total += left; separator = true;
            }
            int n = in.read(b,off,(int)Math.min(left,len));
            if (n == -1) throw new EOFException("Incomplete HTTP chunk"); left -= n; return n;
        }
    }
}
