package com.termux.app.api;

import android.content.Context;
import android.content.Intent;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.JsonWriter;

import java.io.StringWriter;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Streams one JSON object per storage-volume state change, until the caller goes away.
 * <p>
 * SAF can list and read what Android knows about, but nothing says when a card or a drive
 * arrives. Without this a caller either polls the provider list or waits to be told by a human,
 * and the first is work for nothing on a phone where storage rarely changes.
 * <p>
 * The ACTION_MEDIA_* broadcasts are not relied on: on current devices they no longer reach an
 * app at all. StorageManager's volume callback is the supported way, and it also names the
 * volume - label, UUID, mount point - which the broadcast never did.
 */
public class StorageListenerAPI {

    /** How long to wait before a blank line, which is how a caller that exited is noticed. */
    private static final int KEEPALIVE_SECONDS = 60;

    public static void onReceive(final Context context, final Intent intent) {
        ResultReturner.returnData(intent, out -> {
            var events = new LinkedBlockingQueue<String>();
            var storage = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);

            var callback = new StorageManager.StorageVolumeCallback() {
                @Override
                public void onStateChanged(StorageVolume volume) {
                    events.offer(describe(context, "changed", volume));
                }
            };

            storage.registerStorageVolumeCallback(context.getMainExecutor(), callback);
            try {
                // What is attached now comes first, so a caller learns the current state
                // without a second API - the primary volume included, flagged as such.
                for (var volume : storage.getStorageVolumes()) {
                    out.println(describe(context, "present", volume));
                }
                out.flush();
                while (!out.checkError()) {
                    var event = events.poll(KEEPALIVE_SECONDS, TimeUnit.SECONDS);
                    out.println(event == null ? "" : event);
                    out.flush();
                }
            } finally {
                storage.unregisterStorageVolumeCallback(callback);
            }
        });
    }

    private static String describe(Context context, String event, StorageVolume volume) {
        var buffer = new StringWriter();
        try (var out = new JsonWriter(buffer)) {
            out.beginObject();
            out.name("event").value(event);
            out.name("storage").value(volume.getState());
            var directory = volume.getDirectory();
            out.name("path").value(directory == null ? null : directory.getAbsolutePath());
            out.name("uuid").value(volume.getUuid());
            out.name("label").value(volume.getDescription(context));
            out.name("removable").value(volume.isRemovable());
            out.name("primary").value(volume.isPrimary());
            out.endObject();
        } catch (Exception e) {
            return "{\"API_ERROR\":\"" + e.getMessage() + "\"}";
        }
        return buffer.toString();
    }
}
