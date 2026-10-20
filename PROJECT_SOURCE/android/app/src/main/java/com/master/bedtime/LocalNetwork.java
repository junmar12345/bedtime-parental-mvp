package com.master.bedtime;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public final class LocalNetwork {
    private LocalNetwork() {}
    public static String ipv4Address() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if (ip != null && (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("172."))) return ip;
                    }
                }
            }
        } catch (Exception ignored) {}
        return "Unavailable";
    }
}
