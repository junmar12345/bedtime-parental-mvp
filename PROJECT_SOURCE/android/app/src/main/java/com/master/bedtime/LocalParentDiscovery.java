package com.master.bedtime;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Same-Wi-Fi Parent discovery.
 * Order: Android NSD/mDNS, Wi-Fi-bound UDP broadcast, then a limited scan of
 * the connected Wi-Fi subnet for the exact Bedtime local health endpoint.
 * No IP address is entered by the user.
 */
public final class LocalParentDiscovery {
    private static final String TAG = "BedtimeDiscovery";

    public interface Listener {
        void onSearching();
        void onFound(String baseUrl);
        void onLost();
        void onFailed(String message);
    }

    private static final int DISCOVERY_PORT = 39871;
    private static final String PROBE = "BDTM_DISCOVER_V1";
    private static final String RESPONSE_PREFIX = "BDTM_PARENT_V1|";
    private static final int HTTP_PORT = LocalParentServer.PORT;

    private final Context context;
    private final Listener listener;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean resolving = new AtomicBoolean(false);
    private final AtomicBoolean warningShown = new AtomicBoolean(false);

    private volatile String currentBaseUrl = "";
    private volatile Thread broadcastThread;
    private volatile Thread subnetThread;
    private volatile Network wifiNetwork;
    private volatile String ownWifiIpv4 = "";
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;

    public LocalParentDiscovery(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (!active.compareAndSet(false, true)) return;
        currentBaseUrl = "";
        warningShown.set(false);
        listener.onSearching();
        WifiDetails details = readWifiDetails();
        wifiNetwork = details.network;
        ownWifiIpv4 = details.address == null ? "" : details.address.getHostAddress();
        Log.i(TAG, "Discovery started; Wi-Fi address=" + details.addressText()
            + ", interface=" + details.interfaceName + ", prefix=" + details.prefixLength
            + ", networkBound=" + (details.network != null));
        acquireMulticastLock();

        try {
            nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsd != null) {
                discoveryListener = new NsdManager.DiscoveryListener() {
                    @Override public void onDiscoveryStarted(String serviceType) {
                        Log.i(TAG, "NSD discovery started for " + serviceType);
                        listener.onSearching();
                    }

                    @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                        if (!normalizeType(LocalParentServer.SERVICE_TYPE).equals(normalizeType(serviceInfo.getServiceType()))) return;
                        if (!currentBaseUrl.isEmpty() || !resolving.compareAndSet(false, true)) return;
                        Log.i(TAG, "NSD service found: " + serviceInfo.getServiceName());
                        try {
                            nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                                @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                                    Log.w(TAG, "NSD resolve failed: " + errorCode);
                                    resolving.set(false);
                                }

                                @Override public void onServiceResolved(NsdServiceInfo resolved) {
                                    resolving.set(false);
                                    if (!active.get()) return;
                                    InetAddress host = resolved.getHost();
                                    // The local discovery endpoint is intentionally IPv4 because the
                                    // broadcast/subnet fallback also discovers IPv4 Wi-Fi addresses.
                                    if (!(host instanceof Inet4Address)) {
                                        Log.i(TAG, "Ignoring non-IPv4 NSD address; UDP/subnet fallback remains active");
                                        return;
                                    }
                                    int port = resolved.getPort() > 0 ? resolved.getPort() : HTTP_PORT;
                                    String address = host.getHostAddress();
                                    if (address == null || address.trim().isEmpty()) return;
                                    String url = "http://" + address + ":" + port;
                                    if (port == HTTP_PORT) verifyAndFound(url); else found(url);
                                }
                            });
                        } catch (Exception e) {
                            resolving.set(false);
                            Log.w(TAG, "NSD resolve could not start", e);
                        }
                    }

                    @Override public void onServiceLost(NsdServiceInfo serviceInfo) {
                        if (!normalizeType(LocalParentServer.SERVICE_TYPE).equals(normalizeType(serviceInfo.getServiceType()))) return;
                        String old = currentBaseUrl;
                        if (!old.isEmpty()) {
                            currentBaseUrl = "";
                            Log.i(TAG, "NSD Parent service lost");
                            listener.onLost();
                        }
                    }

                    @Override public void onDiscoveryStopped(String serviceType) {
                        Log.i(TAG, "NSD discovery stopped: " + serviceType);
                    }

                    @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                        Log.w(TAG, "NSD start failed: " + errorCode + "; UDP/subnet fallbacks remain active");
                        listener.onSearching();
                        try { nsd.stopServiceDiscovery(this); } catch (Exception ignored) {}
                    }

                    @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                        Log.w(TAG, "NSD stop failed: " + errorCode);
                    }
                };
                nsd.discoverServices(LocalParentServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            } else {
                Log.w(TAG, "Android NSD service is unavailable; fallbacks remain active");
            }
        } catch (Exception e) {
            Log.w(TAG, "NSD discovery could not start; fallbacks remain active", e);
        }

        startBroadcastFallback();
        startSubnetFallback();
    }

    private String normalizeType(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.US);
        while (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("BedtimeChildDiscovery");
                multicastLock.setReferenceCounted(false);
                if (!multicastLock.isHeld()) multicastLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not acquire Wi-Fi multicast lock", e);
        }
    }

    private void startBroadcastFallback() {
        Thread existing = broadcastThread;
        if (existing != null && existing.isAlive()) return;
        Thread worker = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            byte[] probe = PROBE.getBytes(StandardCharsets.UTF_8);
            byte[] buffer = new byte[512];
            while (active.get() && currentBaseUrl.isEmpty()) {
                WifiDetails details = readWifiDetails();
                if (details.network != null) wifiNetwork = details.network;
                try (DatagramSocket socket = new DatagramSocket(null)) {
                    Network network = details.network;
                    socket.setReuseAddress(true);
                    socket.setBroadcast(true);
                    socket.setSoTimeout(750);
                    // Bind first, then pin the unconnected socket to the Wi-Fi network.
                    socket.bind(new InetSocketAddress("0.0.0.0", 0));
                    if (network != null) {
                        try { network.bindSocket(socket); }
                        catch (Exception e) { Log.w(TAG, "Could not bind UDP discovery socket to Wi-Fi; using system route", e); }
                    }
                    sendBroadcastProbes(socket, probe, details);
                    try {
                        DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
                        socket.receive(incoming);
                        String response = new String(incoming.getData(), incoming.getOffset(), incoming.getLength(), StandardCharsets.UTF_8).trim();
                        if (response.startsWith(RESPONSE_PREFIX)) {
                            String baseUrl = response.substring(RESPONSE_PREFIX.length()).trim();
                            Log.i(TAG, "UDP discovery response received: " + baseUrl);
                            if (validBaseUrl(baseUrl)) verifyAndFound(baseUrl);
                        }
                    } catch (SocketTimeoutException ignored) {
                        // No broadcast answer on this cycle; NSD/subnet scan continue.
                    }
                } catch (Exception e) {
                    Log.w(TAG, "UDP broadcast discovery cycle failed", e);
                }

                if (currentBaseUrl.isEmpty() && !warningShown.get()
                    && System.currentTimeMillis() - startedAt >= 18000) {
                    reportFailure("Still searching. Keep the Parent dashboard open, allow Nearby devices, and make sure both phones use the same non-Guest Wi-Fi.");
                }
                if (!sleep(450)) return;
            }
        }, "bedtime-parent-broadcast-discovery");
        worker.setDaemon(true);
        broadcastThread = worker;
        worker.start();
    }

    private void sendBroadcastProbes(DatagramSocket socket, byte[] bytes, WifiDetails details) {
        Set<String> targets = new LinkedHashSet<>();
        targets.add("255.255.255.255");
        if (details.address != null) {
            int prefix = details.prefixLength;
            if (prefix >= 1 && prefix <= 30) {
                long ip = ipv4ToLong(details.address);
                long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
                long broadcast = (ip & mask) | (0xFFFFFFFFL & ~mask);
                targets.add(longToIpv4(broadcast));
            }
        }
        // Some phone builds expose the Wi-Fi link only through NetworkInterface.
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                try { if (!ni.isUp() || ni.isLoopback() || !isWifiInterfaceName(ni.getName())) continue; }
                catch (Exception e) { continue; }
                for (InterfaceAddress entry : ni.getInterfaceAddresses()) {
                    InetAddress address = entry.getAddress();
                    InetAddress broadcast = entry.getBroadcast();
                    if (address instanceof Inet4Address && broadcast != null) targets.add(broadcast.getHostAddress());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not enumerate Wi-Fi broadcast addresses", e);
        }
        for (String target : targets) {
            try {
                InetAddress address = InetAddress.getByName(target);
                socket.send(new DatagramPacket(bytes, bytes.length, address, DISCOVERY_PORT));
            } catch (Exception e) {
                Log.d(TAG, "Broadcast probe failed for " + target + ": " + e.getMessage());
            }
        }
    }

    private void startSubnetFallback() {
        Thread existing = subnetThread;
        if (existing != null && existing.isAlive()) return;
        Thread worker = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            if (!sleep(2200)) return;
            while (active.get() && currentBaseUrl.isEmpty()) {
                WifiDetails details = readWifiDetails();
                if (details.network != null) wifiNetwork = details.network;
                List<String> hosts = subnetCandidates(details);
                if (!hosts.isEmpty()) {
                    Log.i(TAG, "Subnet fallback checking " + hosts.size() + " Wi-Fi hosts on " + details.addressText());
                    scanForParent(hosts, details.network);
                } else {
                    Log.w(TAG, "Subnet fallback has no usable Wi-Fi IPv4 address yet");
                }
                if (currentBaseUrl.isEmpty() && !warningShown.get()
                    && System.currentTimeMillis() - startedAt >= 18000) {
                    reportFailure("Parent not found yet. Check Nearby devices permission, keep the Parent dashboard open, and use the same non-Guest Wi-Fi on both phones. Guest Wi-Fi or client isolation can block phone-to-phone connections.");
                }
                if (!sleep(14000)) return;
            }
        }, "bedtime-parent-subnet-discovery");
        worker.setDaemon(true);
        subnetThread = worker;
        worker.start();
    }

    private void scanForParent(List<String> hosts, Network network) {
        ExecutorService pool = Executors.newFixedThreadPool(32);
        for (String host : hosts) {
            pool.execute(() -> {
                if (!active.get() || !currentBaseUrl.isEmpty()) return;
                String candidate = probeParentHost(host, network);
                if (candidate != null) found(candidate);
            });
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(6500, TimeUnit.MILLISECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private String probeParentHost(String host, Network network) {
        if (host == null || host.trim().isEmpty()) return null;
        if (!ownWifiIpv4.isEmpty() && ownWifiIpv4.equals(host.trim())) {
            Log.i(TAG, "Ignoring this phone's own local address during Parent discovery");
            return null;
        }
        // Do not wait for TCP EOF after the response body. OEM phones sometimes
        // delay closing a local socket; the older readLine-until-EOF approach
        // timed out and discarded a valid Parent health response.
        try (Socket socket = network != null ? network.getSocketFactory().createSocket() : new Socket()) {
            socket.connect(new InetSocketAddress(host, HTTP_PORT), 450);
            socket.setSoTimeout(900);
            String request = "GET /local/health HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            int matched = 0;
            while (headerBytes.size() < 8192) {
                int b = in.read();
                if (b < 0) return null;
                headerBytes.write(b);
                if (matched == 0) matched = (b == '\r') ? 1 : 0;
                else if (matched == 1) matched = (b == '\n') ? 2 : ((b == '\r') ? 1 : 0);
                else if (matched == 2) matched = (b == '\r') ? 3 : 0;
                else if (matched == 3) {
                    if (b == '\n') break;
                    matched = (b == '\r') ? 1 : 0;
                }
            }
            String headers = new String(headerBytes.toByteArray(), StandardCharsets.ISO_8859_1);
            int endHeaders = headers.indexOf("\r\n\r\n");
            if (endHeaders < 0) return null;
            String[] lines = headers.substring(0, endHeaders).split("\r\n");
            if (lines.length == 0 || !(lines[0].startsWith("HTTP/1.1 200") || lines[0].startsWith("HTTP/1.0 200"))) return null;

            int contentLength = -1;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0 && "content-length".equalsIgnoreCase(lines[i].substring(0, colon).trim())) {
                    try { contentLength = Integer.parseInt(lines[i].substring(colon + 1).trim()); }
                    catch (NumberFormatException ignored) { return null; }
                }
            }
            if (contentLength < 0 || contentLength > 4096) return null;
            byte[] bodyBytes = new byte[contentLength];
            int received = 0;
            while (received < contentLength) {
                int n = in.read(bodyBytes, received, contentLength - received);
                if (n < 0) return null;
                received += n;
            }
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            if (body.contains("bedtime-parent-local")) return "http://" + host + ":" + HTTP_PORT;
        } catch (Exception ignored) {
            // Most addresses have no Bedtime server. Failures are expected during the limited scan.
        }
        return null;
    }

    private List<String> subnetCandidates(WifiDetails details) {
        if (details.address == null) return Collections.emptyList();
        int prefix = details.prefixLength;
        if (prefix < 1 || prefix > 30) prefix = 24;
        long ip = ipv4ToLong(details.address);
        long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        long network = ip & mask;
        long broadcast = network | (0xFFFFFFFFL & ~mask);
        long first = network + 1;
        long last = broadcast - 1;
        if (last < first || last - first + 1 > 512) {
            // Very large campus/office subnets are constrained to the local /24 for the fallback.
            prefix = 24;
            mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            network = ip & mask;
            broadcast = network | (0xFFFFFFFFL & ~mask);
            first = network + 1;
            last = broadcast - 1;
        }
        if (last < first || last - first + 1 > 512) return Collections.emptyList();
        List<String> hosts = new ArrayList<>((int)(last - first + 1));
        for (long candidate = first; candidate <= last; candidate++) {
            if (candidate != ip) hosts.add(longToIpv4(candidate));
        }
        return hosts;
    }

    private WifiDetails readWifiDetails() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network wifi = null;
        if (cm != null) {
            try {
                // Prefer the default network when it is Wi-Fi; some OEM Android builds
                // expose multiple Wi-Fi-like Network objects and getAllNetworks() order
                // is not a reliable indication of the connected LAN.
                Network active = cm.getActiveNetwork();
                NetworkCapabilities activeCaps = active == null ? null : cm.getNetworkCapabilities(active);
                if (activeCaps != null && activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifi = active;
                } else {
                    Network anyWifi = null;
                    for (Network candidate : cm.getAllNetworks()) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(candidate);
                        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                        if (anyWifi == null) anyWifi = candidate;
                        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                            wifi = candidate;
                            break;
                        }
                    }
                    if (wifi == null) wifi = anyWifi;
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not inspect Android networks", e);
            }
        }
        if (wifi != null && cm != null) {
            try {
                LinkProperties lp = cm.getLinkProperties(wifi);
                if (lp != null) {
                    String iface = lp.getInterfaceName();
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        if (la.getAddress() instanceof Inet4Address) {
                            return new WifiDetails(wifi, (Inet4Address) la.getAddress(), la.getPrefixLength(), iface);
                        }
                    }
                    return new WifiDetails(wifi, null, 24, iface);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not read Wi-Fi link properties", e);
            }
        }

        // Fallback for OEM Android builds that temporarily omit Wi-Fi IPv4 from
        // LinkProperties/NetworkInterface while the Wi-Fi connection is already up.
        try {
            WifiManager manager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (manager != null) {
                int ip = manager.getConnectionInfo().getIpAddress(); // little-endian IPv4
                byte[] octets = new byte[] {
                    (byte) (ip & 0xff), (byte) ((ip >> 8) & 0xff),
                    (byte) ((ip >> 16) & 0xff), (byte) ((ip >> 24) & 0xff)
                };
                InetAddress address = InetAddress.getByAddress(octets);
                if (ip != 0 && address instanceof Inet4Address && address.isSiteLocalAddress()) {
                    Log.i(TAG, "Using WifiInfo IPv4 fallback " + address.getHostAddress());
                    return new WifiDetails(wifi, (Inet4Address) address, 24, "WifiInfo");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not use WifiInfo IPv4 fallback", e);
        }

        // Last fallback: enumerate interfaces exposed by the Android network stack.
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!isWifiInterfaceName(ni.getName())) continue;
                try { if (!ni.isUp() || ni.isLoopback()) continue; } catch (Exception e) { continue; }
                for (InterfaceAddress entry : ni.getInterfaceAddresses()) {
                    if (entry.getAddress() instanceof Inet4Address && entry.getAddress().isSiteLocalAddress()) {
                        return new WifiDetails(wifi, (Inet4Address) entry.getAddress(), entry.getNetworkPrefixLength(), ni.getName());
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not inspect Wi-Fi interfaces", e);
        }
        return new WifiDetails(wifi, null, 24, "unknown");
    }

    private boolean isWifiInterfaceName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        return n.startsWith("wlan") || n.startsWith("wifi") || n.startsWith("swlan") || n.startsWith("ap");
    }

    private long ipv4ToLong(Inet4Address address) {
        byte[] b = address.getAddress();
        return ((long)(b[0] & 0xff) << 24)
            | ((long)(b[1] & 0xff) << 16)
            | ((long)(b[2] & 0xff) << 8)
            | (long)(b[3] & 0xff);
    }

    private String longToIpv4(long value) {
        return ((value >> 24) & 0xff) + "." + ((value >> 16) & 0xff) + "."
            + ((value >> 8) & 0xff) + "." + (value & 0xff);
    }

    private boolean validBaseUrl(String url) {
        if (url == null || !url.startsWith("http://")) return false;
        if (url.contains("localhost") || url.contains("127.0.0.1")) return false;
        return url.lastIndexOf(':') > "http://".length();
    }

    private void verifyAndFound(String baseUrl) {
        if (!validBaseUrl(baseUrl)) return;
        String hostAndPort = baseUrl.substring("http://".length());
        int colon = hostAndPort.lastIndexOf(':');
        if (colon <= 0) { found(baseUrl); return; }
        final String hostIp = hostAndPort.substring(0, colon);
        if (!ownWifiIpv4.isEmpty() && ownWifiIpv4.equals(hostIp)) {
            Log.i(TAG, "Ignoring NSD result that points back to this phone");
            return;
        }
        Thread verifier = new Thread(() -> {
            String checked = probeParentHost(hostIp, wifiNetwork);
            if (checked != null) found(checked);
        }, "bedtime-parent-verify-discovery");
        verifier.setDaemon(true);
        verifier.start();
    }

    private void found(String baseUrl) {
        if (!validBaseUrl(baseUrl)) return;
        synchronized (this) {
            if (!active.get() || !currentBaseUrl.isEmpty()) return;
            currentBaseUrl = baseUrl;
        }
        Log.i(TAG, "Parent discovered and verified: " + baseUrl);
        listener.onFound(baseUrl);
    }

    private void reportFailure(String message) {
        if (warningShown.compareAndSet(false, true)) {
            Log.w(TAG, message);
            listener.onFailed(message);
        }
    }

    private boolean sleep(long millis) {
        try { Thread.sleep(millis); return true; }
        catch (InterruptedException stop) { Thread.currentThread().interrupt(); return false; }
    }

    public void stop() {
        if (!active.compareAndSet(true, false)) return;
        try {
            if (nsd != null && discoveryListener != null) nsd.stopServiceDiscovery(discoveryListener);
        } catch (Exception ignored) {}
        Thread worker = broadcastThread;
        if (worker != null) worker.interrupt();
        Thread scanner = subnetThread;
        if (scanner != null) scanner.interrupt();
        broadcastThread = null;
        subnetThread = null;
        discoveryListener = null;
        nsd = null;
        wifiNetwork = null;
        ownWifiIpv4 = "";
        currentBaseUrl = "";
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {}
        multicastLock = null;
    }

    private static final class WifiDetails {
        final Network network;
        final Inet4Address address;
        final int prefixLength;
        final String interfaceName;
        WifiDetails(Network network, Inet4Address address, int prefixLength, String interfaceName) {
            this.network = network;
            this.address = address;
            this.prefixLength = prefixLength;
            this.interfaceName = interfaceName == null ? "unknown" : interfaceName;
        }
        String addressText() { return address == null ? "unavailable" : address.getHostAddress(); }
    }
}
