package com.netfixpro.app;

import android.Manifest;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.DhcpInfo;
import android.os.Build;
import android.location.LocationManager;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.JSObject;
import com.getcapacitor.JSArray;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(
    name = "WifiTools",
    permissions = {
        @Permission(alias = "location", strings = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        })
    }
)
public class WifiToolsPlugin extends Plugin {
    @PluginMethod
    public void scanWifi(PluginCall call) {
        if (getPermissionState("location") != com.getcapacitor.PermissionState.GRANTED) {
            requestPermissionForAlias("location", call, "wifiPermissionResult");
            return;
        }
        doWifiScan(call);
    }

    @PermissionCallback
    private void wifiPermissionResult(PluginCall call) {
        if (getPermissionState("location") == com.getcapacitor.PermissionState.GRANTED) {
            doWifiScan(call);
        } else {
            call.reject("Location permission is required to read nearby Wi-Fi scan results.");
        }
    }

    private void doWifiScan(PluginCall call) {
        try {
            Context ctx = getContext();
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            boolean locationOn = Build.VERSION.SDK_INT < 28 || (lm != null &&
                (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)));
            if (!locationOn) {
                call.reject("Turn on Android Location, then scan again.");
                return;
            }
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) {
                call.reject("Turn on Wi-Fi first.");
                return;
            }
            wm.startScan();
            List<ScanResult> scans = wm.getScanResults();
            JSArray arr = new JSArray();
            for (ScanResult s : scans) {
                JSObject item = new JSObject();
                item.put("ssid", s.SSID == null || s.SSID.isEmpty() ? "(Hidden network)" : s.SSID);
                item.put("bssid", s.BSSID);
                item.put("signalDbm", s.level);
                item.put("frequencyMHz", s.frequency);
                int ch = frequencyToChannel(s.frequency);
                item.put("channel", ch);
                item.put("band", s.frequency >= 5925 ? "6 GHz" : (s.frequency >= 4900 ? "5 GHz" : "2.4 GHz"));
                arr.put(item);
            }
            JSObject out = new JSObject();
            out.put("networks", arr);
            out.put("count", arr.length());
            out.put("note", "Android may return cached scan results or limit scans.");
            call.resolve(out);
        } catch (Exception e) {
            call.reject("Wi-Fi scan failed: " + e.getMessage());
        }
    }

    private int frequencyToChannel(int f) {
        if (f >= 2412 && f <= 2472) return (f - 2407) / 5;
        if (f == 2484) return 14;
        if (f >= 5000 && f < 5925) return (f - 5000) / 5;
        if (f >= 5955 && f <= 7115) return (f - 5950) / 5;
        return -1;
    }

    @PluginMethod
    public void scanLan(PluginCall call) {
        WifiManager wm = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) {
            call.reject("Connect to Wi-Fi before scanning your local network.");
            return;
        }
        DhcpInfo dhcp = wm.getDhcpInfo();
        int ip = dhcp.ipAddress;
        int mask = dhcp.netmask;
        if (ip == 0 || mask == 0) {
            call.reject("Could not read Wi-Fi network details. Reconnect to Wi-Fi and retry.");
            return;
        }
        int network = ip & mask;
        int broadcast = network | ~mask;
        int hosts = broadcast - network - 1;
        if (hosts < 1 || hosts > 512) {
            call.reject("This network range is too large or unsupported for a safe scan.");
            return;
        }
        String localIp = intToIp(ip);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<JSObject> found = java.util.Collections.synchronizedList(new ArrayList<JSObject>());
        for (int i = 1; i <= hosts; i++) {
            final int candidate = network + i;
            if (candidate == ip) continue;
            pool.submit(() -> {
                String host = intToIp(candidate);
                boolean alive = false;
                try { alive = InetAddress.getByName(host).isReachable(180); } catch (Exception ignored) {}
                int[] ports = {80, 443, 22, 445, 554, 8080};
                List<Integer> open = new ArrayList<>();
                for (int port : ports) {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(host, port), 120);
                        open.add(port);
                        alive = true;
                    } catch (Exception ignored) {}
                }
                if (alive) {
                    JSObject device = new JSObject();
                    device.put("ip", host);
                    device.put("openPorts", new JSArray(open));
                    found.add(device);
                }
            });
        }
        pool.shutdown();
        new Thread(() -> {
            try {
                if (!pool.awaitTermination(18, TimeUnit.SECONDS)) pool.shutdownNow();
                JSArray arr = new JSArray();
                for (JSObject device : found) arr.put(device);
                JSObject out = new JSObject();
                out.put("localIp", localIp);
                out.put("devices", arr);
                out.put("count", arr.length());
                out.put("note", "Only devices responding to basic local-network probes are listed; some devices may remain hidden.");
                call.resolve(out);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                call.reject("LAN scan was interrupted.");
            }
        }).start();
    }

    private String intToIp(int value) {
        return (value & 0xff) + "." + ((value >> 8) & 0xff) + "." +
            ((value >> 16) & 0xff) + "." + ((value >> 24) & 0xff);
    }
}