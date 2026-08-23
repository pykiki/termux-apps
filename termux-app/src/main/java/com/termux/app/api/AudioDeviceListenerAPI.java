package com.termux.app.api;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Streams one line per audio output route change, until the caller goes away.
 * <p>
 * Nothing in a shell can see the headset go in or the speaker take over again: the route lives in
 * AudioManager and Android tells only registered callbacks. A caller that owns an output stream -
 * a sound server bridged onto AAudio, for instance - otherwise learns about the change by the
 * stream dying under it, which is a repair after the silence rather than before it.
 * <p>
 * Needs no permission, and registers nothing until a caller asks.
 */
public class AudioDeviceListenerAPI {

    // The blank line is how a reader that went away is noticed and the callback
    // released. Half an hour: a reader waits on this stream for as long as the
    // phone is on, and every keepalive is a wake it did not ask for.
    private static final int KEEPALIVE_SECONDS = 1800;

    public static void onReceive(final Context context, final Intent intent) {
        ResultReturner.returnData(intent, out -> {
            var events = new LinkedBlockingQueue<String>();
            var audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

            var callback = new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                    events.offer(route(audio, "added", new AudioDeviceInfo[0]));
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                    // Android still lists a device in getDevices() while its own removal
                    // callback is running - measured on Android 17, buds named in the outputs
                    // of the event announcing they were gone - so a reader deciding on the
                    // preferred output would see no change at the one moment that always
                    // breaks an open stream.
                    events.offer(route(audio, "removed", removed));
                }
            };

            var handler = new Handler(Looper.getMainLooper());
            // The first callback arrives immediately with everything already connected, which is
            // how a caller learns the current route without a second API.
            audio.registerAudioDeviceCallback(callback, handler);
            try {
                while (!out.checkError()) {
                    var event = events.poll(KEEPALIVE_SECONDS, TimeUnit.SECONDS);
                    out.println(event == null ? "" : event);
                    out.flush();
                }
            } finally {
                audio.unregisterAudioDeviceCallback(callback);
            }
        });
    }

    /**
     * The outputs Android would play through now, most preferred first. The order is the one
     * Android itself applies: an attached headset or car wins over the built-in speaker.
     */
    private static String route(AudioManager audio, String change, AudioDeviceInfo[] gone) {
        var devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        var best = "none";
        var rank = -1;
        var all = new StringBuilder();
        for (var device : devices) {
            if (isGone(device, gone)) {
                continue;
            }
            var name = typeName(device.getType());
            if (all.length() > 0) {
                all.append("\",\"");
            }
            all.append(name);
            var r = preference(device.getType());
            if (r > rank) {
                rank = r;
                best = name;
            }
        }
        return "{\"change\":\"" + change + "\",\"route\":\"" + best
            + "\",\"outputs\":[\"" + all + "\"]}";
    }

    /** Whether this is one of the devices the callback is reporting as removed. */
    private static boolean isGone(AudioDeviceInfo device, AudioDeviceInfo[] gone) {
        for (var g : gone) {
            if (g.getId() == device.getId()) {
                return true;
            }
        }
        return false;
    }

    private static int preference(int type) {
        return switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
                 AudioDeviceInfo.TYPE_BLE_SPEAKER -> 5;
            case AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 4;
            // Wireless DeX and any other cast arrive as a remote submix, and
            // that is where Android is playing while one exists - measured on
            // Android 17, reported as type 25 with no name of its own.
            case AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> 4;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 3;
            case AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC,
                 AudioDeviceInfo.TYPE_HDMI_EARC, AudioDeviceInfo.TYPE_DOCK,
                 AudioDeviceInfo.TYPE_HEARING_AID -> 2;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1;
            default -> 0;
        };
    }

    private static String typeName(int type) {
        return switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth-a2dp";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth-sco";
            case AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble-headset";
            case AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble-speaker";
            case AudioDeviceInfo.TYPE_USB_HEADSET -> "usb-headset";
            case AudioDeviceInfo.TYPE_USB_DEVICE -> "usb-device";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired-headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired-headphones";
            case AudioDeviceInfo.TYPE_HDMI -> "hdmi";
            case AudioDeviceInfo.TYPE_HDMI_ARC -> "hdmi-arc";
            case AudioDeviceInfo.TYPE_HDMI_EARC -> "hdmi-earc";
            case AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote-submix";
            case AudioDeviceInfo.TYPE_HEARING_AID -> "hearing-aid";
            case AudioDeviceInfo.TYPE_DOCK -> "dock";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "speaker-safe";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece";
            case AudioDeviceInfo.TYPE_TELEPHONY -> "telephony";
            default -> "type-" + type;
        };
    }
}
