package com.termux.app.api;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.JsonWriter;

import java.io.StringWriter;
import java.net.InetAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

/**
 * Streams one JSON object per line as the default network changes, until the caller goes away.
 * <p>
 * A shell cannot watch this for itself: /proc/net is unreadable by apps since Android 10 and
 * netlink sockets cannot be bound, which leaves polling. Registering a callback here costs
 * nothing while the network is still and reports a change as it happens.
 */
public class NetworkListenerAPI {

    /**
     * How long to wait for an event before writing a blank line, which is how a caller that has
     * exited is noticed. Without it the reading thread would block until the next real change.
     */
    // The blank line is how a reader that went away is noticed and the callback
    // released. Half an hour: a reader waits on this stream for as long as the
    // phone is on, and every keepalive is a wake it did not ask for.
    private static final int KEEPALIVE_SECONDS = 1800;

    public static void onReceive(final Context context, final Intent intent) {
        ResultReturner.returnData(intent, out -> {
            var manager = context.getSystemService(ConnectivityManager.class);
            var events = new LinkedBlockingQueue<String>();
            // Per subscriber, never static: the app process outlives every reader, so a
            // process-wide "last" sent this line to whoever listened first and suppressed
            // it for everyone after - measured, a stream with no capabilities line at all.
            var lastCapabilities = new AtomicReference<String>();
            // The link half is deduplicated too: a reconnection delivers
            // onAvailable and onLinkPropertiesChanged with the same resolvers,
            // and a caller that reacts to resolvers was woken twice for one.
            var lastLink = new AtomicReference<String>();

            // FLAG_INCLUDE_LOCATION_INFO un-redacts the SSID and BSSID, and the framework
            // charges a location access for every callback it then delivers - measured on a
            // Fold, the privacy indicator lit on every signal wobble, for a stream nobody had
            // asked a name of. Off unless the caller asks, so the common case costs none.
            var withName = intent.getBooleanExtra("include_location", false);
            // A caller that only wants the resolvers - the DNS follower - is not woken
            // for a signal bucket or a band change; the link events stay.
            var linkOnly = intent.getBooleanExtra("link_only", false);
            var callback = new ConnectivityManager.NetworkCallback(withName
                    ? ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO : 0) {
                @Override
                public void onAvailable(Network network) {
                    offerLink("available", manager.getLinkProperties(network));
                }

                @Override
                public void onLost(Network network) {
                    // The next network starts from nothing known, whatever it turns out to be.
                    lastLink.set(null);
                    lastCapabilities.set(null);
                    events.offer(describe("lost", null));
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                    offerLink("changed", properties);
                }

                private void offerLink(String event, LinkProperties properties) {
                    var line = describe(event, properties);
                    // The event name is not part of the comparison: "available"
                    // and "changed" carrying the same link are the same news.
                    var key = line.substring(line.indexOf(",\"interface\"") + 1);
                    if (key.equals(lastLink.getAndSet(key))) {
                        return;
                    }
                    events.offer(line);
                }

                /**
                 * Capabilities change on every signal wobble, and each event would wake whatever
                 * is reading the stream. Only a change worth acting on is passed on, and the
                 * filtering happens here where it costs nothing.
                 * <p>
                 * The line itself cannot be the comparison: wifi rate adaptation moves the link
                 * speed constantly and band steering moves the frequency, so a stream that only
                 * ever reported one network still emitted an event every few seconds - measured
                 * on a Fold, seven in fifty seconds, differing in nothing else.
                 */
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    if (linkOnly) {
                        return;
                    }
                    var key = capabilitiesKey(caps, withName);
                    if (key.equals(lastCapabilities.getAndSet(key))) {
                        return;
                    }
                    events.offer(describeCapabilities(caps, withName));
                }
            };

            // The default network is the one the device routes through, so it is the one whose
            // resolvers matter. registerDefaultNetworkCallback reports exactly that one.
            manager.registerDefaultNetworkCallback(callback);
            try {
                while (!out.checkError()) {
                    var event = events.poll(KEEPALIVE_SECONDS, TimeUnit.SECONDS);
                    out.println(event == null ? "" : event);
                    out.flush();
                }
            } finally {
                manager.unregisterNetworkCallback(callback);
            }
        });
    }

    /**
     * The transport, and for wifi what the link reports about itself. Signal is bucketed rather
     * than raw: RSSI moves constantly and nobody wants to be woken for a decibel. The name and
     * the BSSID are the location-sensitive pair and arrive only when they were asked for.
     */
    private static String describeCapabilities(NetworkCapabilities caps, boolean withName) {
        var buffer = new StringWriter();
        try (var out = new JsonWriter(buffer)) {
            out.beginObject();
            out.name("event").value("capabilities");
            out.name("transport").value(transportName(caps));
            if (caps.getTransportInfo() instanceof WifiInfo wifi) {
                if (withName) {
                    // Android wraps the SSID in literal quotes; upstream WifiAPI strips
                    // them too, and leaving them makes every consumer do it instead.
                    out.name("ssid").value(wifi.getSSID().replace("\"", ""));
                    out.name("bssid").value(wifi.getBSSID());
                }
                out.name("link_speed_mbps").value(wifi.getLinkSpeed());
                out.name("frequency_mhz").value(wifi.getFrequency());
                // Five buckets, the same scale the status bar draws.
                out.name("signal").value(WifiManager.calculateSignalLevel(wifi.getRssi(), 5));
            }
            out.endObject();
        } catch (Exception e) {
            return "{\"event\":\"capabilities\",\"API_ERROR\":\"" + e.getMessage() + "\"}";
        }
        return buffer.toString();
    }

    /**
     * What has to change before a reader is worth waking: the transport, the network's identity
     * and the two things anything on screen is drawn from - the signal bucket and the band. Raw
     * link speed and frequency ride along in the event but never trigger one.
     */
    private static String capabilitiesKey(NetworkCapabilities caps, boolean withName) {
        var key = new StringBuilder(transportName(caps));
        if (caps.getTransportInfo() instanceof WifiInfo wifi) {
            if (withName) {
                key.append('\u0000').append(wifi.getSSID()).append('\u0000').append(wifi.getBSSID());
            }
            key.append('\u0000').append(WifiManager.calculateSignalLevel(wifi.getRssi(), 5));
            key.append('\u0000').append(band(wifi.getFrequency()));
        }
        return key.toString();
    }

    /**
     * The band, not the channel: steering between channels of one band is not news, and on 6 GHz
     * it happens every few seconds.
     */
    private static String band(int frequencyMhz) {
        if (frequencyMhz >= 5925) return "6";
        if (frequencyMhz >= 4900) return "5";
        if (frequencyMhz >= 2400) return "2.4";
        return "?";
    }

    private static String transportName(NetworkCapabilities caps) {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "vpn";
        return "other";
    }

    /**
     * The resolvers come from the link itself, so a caller does not have to ask DHCP for what
     * Android already knows.
     */
    private static String describe(String event, LinkProperties properties) {
        var buffer = new StringWriter();
        try (var out = new JsonWriter(buffer)) {
            out.beginObject();
            out.name("event").value(event);
            if (properties != null) {
                out.name("interface").value(properties.getInterfaceName());
                out.name("dns");
                out.beginArray();
                for (InetAddress server : properties.getDnsServers()) {
                    out.value(server.getHostAddress());
                }
                out.endArray();
            }
            out.endObject();
        } catch (Exception e) {
            return "{\"event\":\"" + event + "\",\"API_ERROR\":\"" + e.getMessage() + "\"}";
        }
        return buffer.toString();
    }
}
