package com.master.bedtime;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Enumeration;
import java.util.UUID;
import java.security.SecureRandom;

/**
 * Local Wi-Fi control channel hosted by the Parent phone.
 * The public/cloud control plane remains separate.
 */
public final class LocalParentServer implements AutoCloseable {
    public static final int PORT = 8080;
    public static final String SERVICE_TYPE = "_bedtime._tcp.";
    public static final int DISCOVERY_PORT = 39871;
    private static final String DISCOVERY_PROBE = "BDTM_DISCOVER_V1";
    private static final String DISCOVERY_RESPONSE_PREFIX = "BDTM_PARENT_V1|";
    private static final String TAG = "BedtimeParentLocal";
    private static final String PREFS = "parent_local";
    private static final String CHILDREN_KEY = "children_json";
    private static final String ADMIN_KEY = "admin_token";

    private final Context context;
    private final SharedPreferences sp;
    private final Object lock = new Object();
    private final SecureRandom random = new SecureRandom();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registrationListener;
    private DatagramSocket discoverySocket;
    private Thread discoveryThread;
    private volatile boolean discoveryRunning;
    private WifiManager.MulticastLock multicastLock;

    public LocalParentServer(Context context) {
        this.context = context.getApplicationContext();
        this.sp = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ensureAdminToken();
    }


    private String childrenKey() {
        String parentId = context.getSharedPreferences("parent", Context.MODE_PRIVATE).getString("parentId", "");
        return parentId.isEmpty() ? CHILDREN_KEY : CHILDREN_KEY + "_" + parentId;
    }
    public String adminToken() {
        return sp.getString(ADMIN_KEY, "");
    }

    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    private void ensureAdminToken() {
        if (adminToken().isEmpty()) {
            sp.edit().putString(ADMIN_KEY, "parentlocal_" + UUID.randomUUID().toString().replace("-", "")) .apply();
        }
    }

    public void start() {
        if (running) return;
        ensureAdminToken();
        try {
            // Explicitly listen on all IPv4 interfaces, not just loopback. This is
            // required so another phone on the same Wi-Fi can reach this service.
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", PORT), 32);
            running = true;
            acceptThread = new Thread(this::acceptLoop, "bedtime-local-parent-server");
            acceptThread.start();
            acquireMulticastLock();
            registerNsd();
            startUdpDiscoveryListener();
            Log.i(TAG, "Local Parent server ready on port " + PORT);
        } catch (Exception e) {
            running = false;
            closeSocket();
            Log.e(TAG, "Could not start local Parent server on port " + PORT, e);
        }
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("BedtimeParentDiscovery");
                multicastLock.setReferenceCounted(false);
                if (!multicastLock.isHeld()) multicastLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not acquire Wi-Fi multicast lock", e);
        }
    }

    private void startUdpDiscoveryListener() {
        if (discoveryRunning) return;
        try {
            DatagramSocket socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            // Bind the wildcard UDP port first, then pin traffic to Wi-Fi. Binding
            // after Network.bindSocket can fail on some OEM Android builds.
            socket.bind(new InetSocketAddress("0.0.0.0", DISCOVERY_PORT));
            Network wifi = activeWifiNetwork();
            if (wifi != null) {
                try { wifi.bindSocket(socket); }
                catch (Exception e) { Log.w(TAG, "Could not bind discovery listener to Wi-Fi network; wildcard route remains", e); }
            }
            discoverySocket = socket;
            discoveryRunning = true;
            discoveryThread = new Thread(() -> discoveryLoop(socket), "bedtime-parent-udp-discovery");
            discoveryThread.setDaemon(true);
            discoveryThread.start();
            Log.i(TAG, "UDP discovery fallback listening on " + DISCOVERY_PORT);
        } catch (Exception e) {
            discoveryRunning = false;
            Log.e(TAG, "Could not start UDP discovery fallback", e);
        }
    }

    private void discoveryLoop(DatagramSocket socket) {
        byte[] buffer = new byte[256];
        while (discoveryRunning && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String request = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
                if (!DISCOVERY_PROBE.equals(request)) continue;
                String ip = localWifiIpv4();
                if (ip.isEmpty()) continue;
                byte[] answer = (DISCOVERY_RESPONSE_PREFIX + "http://" + ip + ":" + PORT).getBytes(StandardCharsets.UTF_8);
                DatagramPacket response = new DatagramPacket(answer, answer.length, packet.getAddress(), packet.getPort());
                socket.send(response);
            } catch (Exception e) {
                if (discoveryRunning && !socket.isClosed()) Log.w(TAG, "UDP discovery packet failed", e);
            }
        }
    }

    private Network activeWifiNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            Network active = cm.getActiveNetwork();
            NetworkCapabilities activeCaps = active == null ? null : cm.getNetworkCapabilities(active);
            if (activeCaps != null && activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return active;
            }
            Network anyWifi = null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                if (anyWifi == null) anyWifi = n;
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return n;
            }
            return anyWifi;
        } catch (Exception e) {
            Log.w(TAG, "Could not identify active Wi-Fi network", e);
        }
        return null;
    }

    private String localWifiIpv4() {
        // Prefer the IPv4 address Android reports for the active Wi-Fi network.
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network wifi = activeWifiNetwork();
            if (cm != null && wifi != null) {
                LinkProperties lp = cm.getLinkProperties(wifi);
                if (lp != null) {
                    for (LinkAddress entry : lp.getLinkAddresses()) {
                        InetAddress address = entry.getAddress();
                        if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                            return address.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read IPv4 address from Wi-Fi network", e);
        }

        // OEM Wi-Fi stacks sometimes do not expose IPv4 through LinkProperties
        // immediately after association. WifiInfo is a compatibility fallback.
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                int ip = wifi.getConnectionInfo().getIpAddress(); // legacy API: IPv4 in little-endian order
                byte[] octets = new byte[] {
                    (byte) (ip & 0xff), (byte) ((ip >> 8) & 0xff),
                    (byte) ((ip >> 16) & 0xff), (byte) ((ip >> 24) & 0xff)
                };
                InetAddress address = InetAddress.getByAddress(octets);
                if (ip != 0 && address instanceof Inet4Address && address.isSiteLocalAddress()) {
                    Log.i(TAG, "Using WifiInfo IPv4 fallback " + address.getHostAddress());
                    return address.getHostAddress();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not use WifiInfo IPv4 fallback", e);
        }

        String fallback = "";
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                try { if (!ni.isUp() || ni.isLoopback()) continue; } catch (Exception e) { continue; }
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase(java.util.Locale.US);
                boolean wifiInterface = name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("swlan") || name.startsWith("ap");
                for (InterfaceAddress entry : ni.getInterfaceAddresses()) {
                    InetAddress address = entry.getAddress();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || !address.isSiteLocalAddress()) continue;
                    String candidate = address.getHostAddress();
                    if (wifiInterface) return candidate;
                    if (fallback.isEmpty()) fallback = candidate;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to find local Wi-Fi IPv4 address", e);
        }
        return fallback;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                new Thread(() -> handle(socket), "bedtime-local-client").start();
            } catch (Exception ignored) {
                if (running) {
                    try { Thread.sleep(150); } catch (InterruptedException stop) { return; }
                }
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(8000);
            java.io.InputStream raw = new java.io.BufferedInputStream(s.getInputStream());
            java.io.ByteArrayOutputStream headerOut = new java.io.ByteArrayOutputStream();
            int matched = 0, b;
            while ((b = raw.read()) != -1) {
                headerOut.write(b);
                // Stop immediately on the fourth byte of CRLFCRLF. The old parser
                // waited for one EXTRA byte, making body-less GET requests time out.
                if (matched == 0) matched = (b == '\r') ? 1 : 0;
                else if (matched == 1) matched = (b == '\n') ? 2 : ((b == '\r') ? 1 : 0);
                else if (matched == 2) matched = (b == '\r') ? 3 : 0;
                else if (matched == 3) {
                    if (b == '\n') { matched = 4; break; }
                    matched = (b == '\r') ? 1 : 0;
                }
                if (headerOut.size() > 16384) return;
            }

            byte[] headerBytes = headerOut.toByteArray();
            String headerText = new String(headerBytes, StandardCharsets.ISO_8859_1);
            int endHeaders = headerText.indexOf("\r\n\r\n");
            if (endHeaders < 0) { writeJson(s, 400, error("Bad request")); return; }
            String[] lines = headerText.substring(0, endHeaders).split("\r\n");
            if (lines.length == 0) { writeJson(s, 400, error("Bad request")); return; }
            String[] first = lines[0].split(" ");
            if (first.length < 2) { writeJson(s, 400, error("Bad request")); return; }
            String method = first[0].toUpperCase();
            String path = first[1];
            Map<String, String> headers = new HashMap<>();
            int contentLength = 0;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    String k = lines[i].substring(0, colon).trim().toLowerCase();
                    String v = lines[i].substring(colon + 1).trim();
                    headers.put(k, v);
                    if ("content-length".equals(k)) {
                        try { contentLength = Integer.parseInt(v); } catch (Exception ignored) {}
                    }
                }
            }

            int already = headerBytes.length - (endHeaders + 4);
            java.io.ByteArrayOutputStream bodyOut = new java.io.ByteArrayOutputStream(Math.max(0, contentLength));
            if (already > 0) bodyOut.write(headerBytes, endHeaders + 4, already);
            byte[] buf = new byte[8192];
            while (bodyOut.size() < contentLength) {
                int n = raw.read(buf, 0, Math.min(buf.length, contentLength - bodyOut.size()));
                if (n < 0) break;
                bodyOut.write(buf, 0, n);
            }
            String body = bodyOut.toString(StandardCharsets.UTF_8);

            try {
                JSONObject result = route(method, path, headers, body);
                int status = result.has("_status") ? result.optInt("_status", 200) : 200;
                result.remove("_status");
                writeJson(s, status, result);
            } catch (HttpException he) {
                writeJson(s, he.status, error(he.getMessage()));
            }
        } catch (Exception ignored) {
            try { writeJson(socket, 500, error("Local server error")); } catch (Exception ignored2) {}
        }
    }

    private JSONObject route(String method, String path, Map<String, String> headers, String body) throws Exception {
        // Keep Parent-side state aligned with the daily 05:00 unlock before any request
        // (including explicit LOCK commands issued after 05:00).
        try { DailyAutoUnlock.applyParentIfDue(context); }
        catch (Exception e) { Log.w(TAG, "Daily auto-unlock check failed; will retry on the next request", e); }
        normalizeAllExpiredUnlocks();
        refreshBillingStatuses();
        if ("OPTIONS".equals(method)) return new JSONObject().put("ok", true);
        if ("GET".equals(method) && "/local/health".equals(path)) {
            return new JSONObject().put("ok", true).put("service", "bedtime-parent-local").put("port", PORT);
        }
        if ("POST".equals(method) && "/local/child/register".equals(path)) {
            JSONObject b = parse(body);
            String name = b.optString("name", "Child").trim();
            if (name.isEmpty()) name = "Child";
            String deviceKey = b.optString("deviceKey", "").trim();
            String preferredCode = b.optString("code", "").trim();
            boolean cloudFallbackEnabled = b.optBoolean("cloudFallbackEnabled", false);

            synchronized (lock) {
                JSONArray a = loadChildren();
                if (!deviceKey.isEmpty()) {
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject existing = a.getJSONObject(i);
                        if (deviceKey.equals(existing.optString("deviceKey", ""))) {
                            existing.put("name", name);
                            existing.put("cloudFallbackEnabled", cloudFallbackEnabled);
                            if (preferredCode.matches("\\d{6}") && codeAvailable(a, preferredCode, deviceKey)) {
                                existing.put("code", preferredCode);
                            }
                            existing.put("token", "child_" + UUID.randomUUID().toString().replace("-", ""));
                            existing.put("lastSeenAt", System.currentTimeMillis());
                            saveChildren(a);
                            return new JSONObject().put("child", publicChild(existing)).put("token", existing.getString("token"));
                        }
                    }
                }

                JSONObject child = new JSONObject();
                child.put("childId", "C-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
                child.put("name", name);
                child.put("code", preferredCode.matches("\\d{6}") && codeAvailable(a, preferredCode, "") ? preferredCode : uniqueCode());
                child.put("token", "child_" + UUID.randomUUID().toString().replace("-", ""));
                child.put("deviceKey", deviceKey);
                child.put("cloudFallbackEnabled", cloudFallbackEnabled);
                child.put("paired", false);
                child.put("online", true);
                child.put("lastSeenAt", System.currentTimeMillis());
                child.put("locked", false);
                child.put("command", "UNLOCK");
                a.put(child);
                saveChildren(a);
                return new JSONObject().put("child", publicChild(child)).put("token", child.getString("token"));
            }
        }
        if ("GET".equals(method) && "/local/children".equals(path)) {
            requireAdmin(headers);
            return new JSONObject().put("children", publicChildren());
        }
        if ("POST".equals(method) && "/local/pair".equals(path)) {
            requireAdmin(headers);
            JSONObject b = parse(body);
            String code = b.optString("code", "").trim();
            if (!code.matches("\\d{6}")) return status(error("Child code must be 6 digits"), 400);
            synchronized (lock) {
                JSONArray a = loadChildren();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject c = a.getJSONObject(i);
                    if (code.equals(c.optString("code"))) {
                        long pairAt = System.currentTimeMillis();
                        if (c.optInt("billingOrder", 0) <= 0) {
                            int maxOrder = 0;
                            for (int j = 0; j < a.length(); j++) {
                                JSONObject item = a.optJSONObject(j);
                                if (item != null) maxOrder = Math.max(maxOrder, item.optInt("billingOrder", 0));
                            }
                            int order = maxOrder + 1;
                            c.put("billingOrder", order).put("pairedAt", pairAt);
                            if (order == 1) {
                                c.put("billingStatus", "TRIAL")
                                    .put("trialStartedAt", pairAt)
                                    .put("trialEndsAt", pairAt + 24L * 60L * 60L * 1000L)
                                    .put("amountDue", 0);
                            } else {
                                c.put("billingStatus", "PENDING_PAYMENT")
                                    .put("amountDue", order == 2 ? 300 : 50);
                            }
                        }
                        c.put("paired", true);
                        c.put("lastSeenAt", pairAt);
                        saveChildren(a);
                        return new JSONObject().put("ok", true).put("child", publicChild(c));
                    }
                }
            }
            return status(error("Child code not found on Parent Wi-Fi"), 404);
        }
        if (path.startsWith("/local/children/") && path.endsWith("/billing") && "POST".equals(method)) {
            requireAdmin(headers);
            String childId = path.substring("/local/children/".length(), path.length() - "/billing".length());
            JSONObject b = parse(body);
            synchronized (lock) {
                JSONArray a = loadChildren();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject c = a.getJSONObject(i);
                    if (!childId.equals(c.optString("childId"))) continue;
                    if (b.has("billingOrder")) c.put("billingOrder", b.optInt("billingOrder", c.optInt("billingOrder", 0)));
                    if (b.has("billingStatus")) c.put("billingStatus", b.optString("billingStatus", c.optString("billingStatus", "")));
                    if (b.has("trialStartedAt")) c.put("trialStartedAt", b.optLong("trialStartedAt", 0L));
                    if (b.has("trialEndsAt")) c.put("trialEndsAt", b.optLong("trialEndsAt", 0L));
                    if (b.has("paidUntil")) c.put("paidUntil", b.optLong("paidUntil", 0L));
                    if (b.has("paidAt")) c.put("paidAt", b.optLong("paidAt", 0L));
                    if (b.has("paidAmount")) c.put("paidAmount", b.optInt("paidAmount", 0));
                    if (b.has("amountDue")) c.put("amountDue", b.optInt("amountDue", 0));
                    saveChildren(a);
                    return new JSONObject().put("ok", true).put("child", publicChild(c));
                }
            }
            return status(error("Child not found"), 404);
        }

        if (path.startsWith("/local/children/") && "DELETE".equals(method)) {
            requireAdmin(headers);
            String childId = path.substring("/local/children/".length());
            synchronized (lock) {
                JSONArray a = loadChildren();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject c = a.getJSONObject(i);
                    if (childId.equals(c.optString("childId"))) {
                        boolean online = System.currentTimeMillis() - c.optLong("lastSeenAt", 0) <= 12000;
                        if (online) return status(error("Child is still online. Only offline children can be removed."), 409);
                        a.remove(i);
                        saveChildren(a);
                        return new JSONObject().put("ok", true);
                    }
                }
            }
            return status(error("Child not found"), 404);
        }
        if (path.startsWith("/local/children/") && path.endsWith("/command") && "POST".equals(method)) {
            requireAdmin(headers);
            String childId = path.substring("/local/children/".length(), path.length() - "/command".length());
            JSONObject b = parse(body);
            String command = b.optString("command", "").trim().toUpperCase();
            if (!"LOCK".equals(command) && !"UNLOCK".equals(command)) return status(error("Command must be LOCK or UNLOCK"), 400);
            long unlockAt = "LOCK".equals(command) ? b.optLong("unlockAt", 0L) : 0L;
            if (unlockAt > 0L && unlockAt <= System.currentTimeMillis()) return status(error("Auto Unlock time must be in the future"), 400);
            synchronized (lock) {
                JSONArray a = loadChildren();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject c = a.getJSONObject(i);
                    if (childId.equals(c.optString("childId"))) {
                        if (!c.optBoolean("paired", false)) return status(error("Child is not paired"), 409);
                        String billingStatus = c.optString("billingStatus", "");
                        if ("LOCK".equals(command) && billingStatus.trim().isEmpty()) {
                            return status(error("BILLING_SYNC_REQUIRED: refresh Parent while connected to Internet before LOCK control."), 409);
                        }
                        if ("LOCK".equals(command) && ("EXPIRED".equals(billingStatus) || "PENDING_PAYMENT".equals(billingStatus))) {
                            int amount = c.optInt("amountDue", c.optInt("billingOrder", 0) == 2 ? 300 : (c.optInt("billingOrder", 0) >= 3 ? 50 : 300));
                            return status(error("PAYMENT_REQUIRED: pay ₱" + amount + " for this Child before LOCK control."), 402);
                        }
                        c.put("command", command);
                        c.put("locked", "LOCK".equals(command));
                        c.put("unlockAt", unlockAt);
                        c.put("lastCommandAt", System.currentTimeMillis());
                        saveChildren(a);
                        return new JSONObject().put("ok", true).put("child", publicChild(c));
                    }
                }
            }
            return status(error("Child not found"), 404);
        }
        if ("POST".equals(method) && "/local/child/heartbeat".equals(path)) {
            JSONObject child = requireChild(headers);
            JSONObject heartbeatBody = parse(body);
            // Optional additive metadata: old clients can keep posting empty heartbeats.
            // Child preference sync never changes pairing state, command, or auth token.
            if (heartbeatBody.has("cloudFallbackEnabled")) {
                child.put("cloudFallbackEnabled", heartbeatBody.optBoolean("cloudFallbackEnabled", false));
            }
            normalizeExpiredUnlock(child);
            child.put("lastSeenAt", System.currentTimeMillis());
            saveChild(child);
            return new JSONObject()
                .put("ok", true)
                .put("command", child.optString("command", "UNLOCK"))
                .put("locked", child.optBoolean("locked", false))
                .put("unlockAt", child.optLong("unlockAt", 0L))
                .put("lastCommandAt", child.optLong("lastCommandAt", 0L))
                .put("parentPaired", child.optBoolean("paired", false));
        }
        if ("GET".equals(method) && "/local/child/command".equals(path)) {
            JSONObject child = requireChild(headers);
            normalizeExpiredUnlock(child);
            saveChild(child);
            return new JSONObject()
                .put("command", child.optString("command", "UNLOCK"))
                .put("locked", child.optBoolean("locked", false))
                .put("unlockAt", child.optLong("unlockAt", 0L))
                .put("lastCommandAt", child.optLong("lastCommandAt", 0L))
                .put("parentPaired", child.optBoolean("paired", false));
        }
        if ("POST".equals(method) && "/local/child/unpair".equals(path)) {
            JSONObject child = requireChild(headers);
            child.put("paired", false).put("command", "UNLOCK").put("locked", false).put("unlockAt", 0L).put("lastSeenAt", System.currentTimeMillis());
            saveChild(child);
            return new JSONObject().put("ok", true);
        }
        return status(error("Not found"), 404);
    }

    private void requireAdmin(Map<String, String> headers) throws Exception {
        if (!adminToken().equals(headers.get("x-parent-local-token"))) throw new HttpException(401, "Unauthorized");
    }

    private JSONObject requireChild(Map<String, String> headers) throws Exception {
        String t = headers.get("x-child-local-token");
        if (t == null || t.isEmpty()) throw new HttpException(401, "Unauthorized");
        synchronized (lock) {
            JSONArray a = loadChildren();
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.getJSONObject(i);
                if (t.equals(c.optString("token"))) return c;
            }
        }
        throw new HttpException(401, "Invalid Child session");
    }

    private JSONObject publicChild(JSONObject c) throws Exception {
        long last = c.optLong("lastSeenAt", 0);
        boolean online = last > 0 && System.currentTimeMillis() - last <= 12000;
        return new JSONObject()
            .put("childId", c.optString("childId"))
            .put("code", c.optString("code"))
            .put("name", c.optString("name"))
            .put("paired", c.optBoolean("paired", false))
            .put("online", online)
            .put("lastSeenAt", last)
            .put("lastCommandAt", c.optLong("lastCommandAt", 0L))
            .put("unlockAt", c.optLong("unlockAt", 0L))
            .put("cloudFallbackEnabled", c.optBoolean("cloudFallbackEnabled", true))
            .put("billingOrder", c.optInt("billingOrder", 0))
            .put("billingStatus", c.optString("billingStatus", ""))
            .put("pairedAt", c.optLong("pairedAt", 0L))
            .put("trialStartedAt", c.optLong("trialStartedAt", 0L))
            .put("trialEndsAt", c.optLong("trialEndsAt", 0L))
            .put("paidUntil", c.optLong("paidUntil", 0L))
            .put("paidAt", c.optLong("paidAt", 0L))
            .put("paidAmount", c.optInt("paidAmount", 0))
            .put("amountDue", c.optInt("amountDue", 0))
            .put("locked", c.optBoolean("locked", false));
    }

    private boolean normalizeExpiredUnlock(JSONObject child) {
        try {
            long unlockAt = child.optLong("unlockAt", 0L);
            if ("LOCK".equalsIgnoreCase(child.optString("command", "UNLOCK"))
                    && unlockAt > 0L && System.currentTimeMillis() >= unlockAt) {
                child.put("command", "UNLOCK");
                child.put("locked", false);
                child.put("unlockAt", 0L);
                // Keep the scheduled time as the unlock event timestamp so the Child can
                // distinguish it from a newer manual LOCK after the timer has completed.
                child.put("lastCommandAt", unlockAt);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not normalize expired Auto Unlock", e);
        }
        return false;
    }

    private void normalizeAllExpiredUnlocks() {
        synchronized (lock) {
            try {
                JSONArray a = loadChildren();
                boolean changed = false;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject child = a.optJSONObject(i);
                    if (child != null && normalizeExpiredUnlock(child)) changed = true;
                }
                if (changed) saveChildren(a);
            } catch (Exception e) {
                Log.w(TAG, "Could not update expired Auto Unlock state", e);
            }
        }
    }

    private void refreshBillingStatuses() {
        synchronized (lock) {
            JSONArray a = loadChildren();
            long at = System.currentTimeMillis();
            boolean changed = false;
            JSONObject first = null;
            JSONObject second = null;
            for (int i = 0; i < a.length(); i++) {
                JSONObject item = a.optJSONObject(i);
                if (item == null) continue;
                if (item.optInt("billingOrder", 0) == 1) first = item;
                if (item.optInt("billingOrder", 0) == 2) second = item;
            }
            long firstTrialEnd = first == null ? 0L : first.optLong("trialEndsAt", 0L);
            boolean secondPaidDuringTrial = second != null && firstTrialEnd > 0L
                && second.optLong("paidAt", 0L) > 0L && second.optLong("paidAt", 0L) < firstTrialEnd;
            for (int i = 0; i < a.length(); i++) {
                JSONObject child = a.optJSONObject(i);
                if (child == null) continue;
                String oldStatus = child.optString("billingStatus", "");
                String nextStatus = oldStatus;
                int order = child.optInt("billingOrder", 0);
                long trialEnd = child.optLong("trialEndsAt", 0L);
                long paidUntil = child.optLong("paidUntil", 0L);
                int amountDue = child.optInt("amountDue", 0);
                if ("TRIAL".equals(oldStatus) && trialEnd > 0L && at >= trialEnd) {
                    nextStatus = "EXPIRED";
                    amountDue = (order == 1 && secondPaidDuringTrial) ? 100 : (order == 1 ? 300 : (order == 2 ? 300 : 50));
                } else if ("ACTIVE".equals(oldStatus) && paidUntil > 0L && at >= paidUntil) {
                    nextStatus = "EXPIRED";
                    int paid = child.optInt("paidAmount", 0);
                    amountDue = paid > 0 ? paid : (order == 2 ? 300 : (order >= 3 ? 50 : 300));
                } else if ("PENDING_PAYMENT".equals(oldStatus)) {
                    amountDue = order == 2 ? 300 : (order >= 3 ? 50 : 300);
                } else if ("TRIAL".equals(oldStatus) || ("ACTIVE".equals(oldStatus) && (paidUntil == 0L || at < paidUntil))) {
                    amountDue = 0;
                }
                if (!nextStatus.equals(oldStatus) || amountDue != child.optInt("amountDue", 0)) {
                    try {
                        child.put("billingStatus", nextStatus);
                        child.put("amountDue", amountDue);
                        changed = true;
                    } catch (Exception e) {
                        Log.w(TAG, "Could not refresh Child billing status", e);
                    }
                }
            }
            if (changed) saveChildren(a);
        }
    }

    private JSONArray publicChildren() throws Exception {
        JSONArray out = new JSONArray();
        synchronized (lock) {
            JSONArray a = loadChildren();
            boolean changed = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject child = a.optJSONObject(i);
                if (child == null) continue;
                if (normalizeExpiredUnlock(child)) changed = true;
                out.put(publicChild(child));
            }
            if (changed) saveChildren(a);
        }
        return out;
    }

    private JSONArray loadChildren() {
        String key = childrenKey();
        String raw = sp.getString(key, null);
        if (raw == null) {
            // One-time non-destructive migration from builds that stored the family roster
            // under the original unscoped children_json key.
            raw = sp.getString(CHILDREN_KEY, "[]");
            if (!CHILDREN_KEY.equals(key)) {
                sp.edit().putString(key, raw).apply();
                Log.i(TAG, "Migrated legacy Child roster into the signed-in Parent's local roster key");
            }
        }
        try { return new JSONArray(raw == null ? "[]" : raw); }
        catch (Exception e) { Log.e(TAG, "Stored Child roster was not valid JSON", e); return new JSONArray(); }
    }

    private void saveChildren(JSONArray a) {
        sp.edit().putString(childrenKey(), a.toString()).apply();
    }

    private void addChild(JSONObject child) {
        synchronized (lock) {
            JSONArray a = loadChildren();
            a.put(child);
            saveChildren(a);
        }
    }

    private void saveChild(JSONObject child) {
        synchronized (lock) {
            JSONArray a = loadChildren();
            for (int i = 0; i < a.length(); i++) {
                try {
                    JSONObject c = a.getJSONObject(i);
                    if (c.optString("childId").equals(child.optString("childId"))) { a.put(i, child); break; }
                } catch (Exception ignored) {}
            }
            saveChildren(a);
        }
    }

    private boolean codeAvailable(JSONArray children, String candidate, String ownDeviceKey) {
        try {
            for (int i = 0; i < children.length(); i++) {
                JSONObject item = children.getJSONObject(i);
                if (!candidate.equals(item.optString("code", ""))) continue;
                if (!ownDeviceKey.isEmpty() && ownDeviceKey.equals(item.optString("deviceKey", ""))) continue;
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String uniqueCode() throws Exception {
        for (int tries = 0; tries < 100; tries++) {
            int n = 100000 + random.nextInt(900000);
            String code = String.valueOf(n);
            boolean exists = false;
            JSONArray a = loadChildren();
            for (int i = 0; i < a.length(); i++) if (code.equals(a.getJSONObject(i).optString("code"))) { exists = true; break; }
            if (!exists) return code;
        }
        throw new Exception("Unable to generate Child code");
    }

    private JSONObject parse(String body) {
        try { return new JSONObject(body == null || body.isEmpty() ? "{}" : body); } catch (Exception e) { return new JSONObject(); }
    }

    private JSONObject error(String msg) {
        JSONObject o = new JSONObject();
        try { o.put("error", msg); } catch (Exception ignored) {}
        return o;
    }

    private JSONObject status(JSONObject o, int code) {
        try { o.put("_status", code); } catch (Exception ignored) {}
        return o;
    }

    private void writeJson(Socket s, int status, JSONObject obj) throws Exception {
        byte[] data = obj.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        String text = "HTTP/1.1 " + status + " " + reason(status) + "\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Cache-Control: no-store\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Headers: content-type,x-parent-local-token,x-child-local-token\r\n" +
            "Access-Control-Allow-Methods: GET,POST,OPTIONS\r\n" +
            "Content-Length: " + data.length + "\r\n" +
            "Connection: close\r\n\r\n";
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 409: return "Conflict";
            default: return "Error";
        }
    }

    private void registerNsd() {
        try {
            nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return;
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("Bedtime Parent");
            info.setServiceType(SERVICE_TYPE);
            info.setPort(PORT);
            registrationListener = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                    Log.i(TAG, "NSD Parent service registered: " + serviceInfo.getServiceName());
                }
                @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.e(TAG, "NSD Parent service registration failed: " + errorCode);
                }
                @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                    Log.i(TAG, "NSD Parent service unregistered");
                }
                @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, "NSD Parent service unregistration failed: " + errorCode);
                }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        } catch (Exception e) {
            Log.e(TAG, "Could not register NSD Parent service", e);
        }
    }

    private void unregisterNsd() {
        try {
            if (nsd != null && registrationListener != null) nsd.unregisterService(registrationListener);
        } catch (Exception ignored) {}
        registrationListener = null;
        nsd = null;
    }

    private void closeSocket() {
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        serverSocket = null;
    }

    @Override public void close() {
        running = false;
        unregisterNsd();
        discoveryRunning = false;
        if (discoverySocket != null) {
            try { discoverySocket.close(); } catch (Exception ignored) {}
        }
        discoverySocket = null;
        if (discoveryThread != null) discoveryThread.interrupt();
        discoveryThread = null;
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {}
        multicastLock = null;
        closeSocket();
        if (acceptThread != null) acceptThread.interrupt();
        acceptThread = null;
    }

    private static final class HttpException extends Exception {
        final int status;
        HttpException(int status, String message) { super(message); this.status = status; }
    }
}
