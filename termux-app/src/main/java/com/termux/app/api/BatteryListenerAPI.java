package com.termux.app.api;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.JsonWriter;

import java.io.StringWriter;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Streams one JSON object per meaningful battery change, until the caller goes away.
 * <p>
 * BatteryStatus answers what the level is now; a caller that wants to follow it has no choice
 * but to ask again on a timer, which is a watcher whose only purpose is to talk about the
 * battery. ACTION_BATTERY_CHANGED is a broadcast, so the change can be pushed instead.
 */
public class BatteryListenerAPI {

    /** How long to wait before a blank line, which is how a caller that exited is noticed. */
    private static final int KEEPALIVE_SECONDS = 60;

    public static void onReceive(final Context context, final Intent intent) {
        ResultReturner.returnData(intent, out -> {
            var events = new LinkedBlockingQueue<String>();
            final String[] last = { null };

            var receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent battery) {
                    var line = describe(battery);
                    // The broadcast fires on temperature and voltage drift too. Filtering
                    // here costs nothing; filtering in the reader would cost a wakeup first.
                    synchronized (last) {
                        if (line.equals(last[0])) {
                            return;
                        }
                        last[0] = line;
                    }
                    events.offer(line);
                }
            };

            context.registerReceiver(receiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            try {
                while (!out.checkError()) {
                    var event = events.poll(KEEPALIVE_SECONDS, TimeUnit.SECONDS);
                    out.println(event == null ? "" : event);
                    out.flush();
                }
            } finally {
                context.unregisterReceiver(receiver);
            }
        });
    }

    /**
     * Percentage and charging state only. Temperature and voltage move constantly and nobody
     * wants to be woken for a tenth of a degree; BatteryStatus still reports them on request.
     */
    private static String describe(Intent battery) {
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);

        var buffer = new StringWriter();
        try (var out = new JsonWriter(buffer)) {
            out.beginObject();
            out.name("percentage").value(scale > 0 ? (level * 100 / scale) : -1);
            out.name("status").value(statusName(status));
            out.name("plugged").value(pluggedName(plugged));
            out.endObject();
        } catch (Exception e) {
            return "{\"API_ERROR\":\"" + e.getMessage() + "\"}";
        }
        return buffer.toString();
    }

    private static String statusName(int status) {
        return switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING";
            case BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING";
            case BatteryManager.BATTERY_STATUS_FULL -> "FULL";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING";
            default -> "UNKNOWN";
        };
    }

    private static String pluggedName(int plugged) {
        return switch (plugged) {
            case 0 -> "UNPLUGGED";
            case BatteryManager.BATTERY_PLUGGED_AC -> "PLUGGED_AC";
            case BatteryManager.BATTERY_PLUGGED_USB -> "PLUGGED_USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS -> "PLUGGED_WIRELESS";
            default -> "UNKNOWN";
        };
    }
}
