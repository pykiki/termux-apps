package com.termux.app.api;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Streams one line per screen state change, until the caller goes away.
 * <p>
 * The value is not knowing the screen is on; it is letting a caller stop working when nobody is
 * looking. Anything that ticks while the screen is off spends battery on an audience of none,
 * and without this there is no way for a shell to know.
 */
public class ScreenListenerAPI {

    /** How long to wait before a blank line, which is how a caller that exited is noticed. */
    private static final int KEEPALIVE_SECONDS = 60;

    public static void onReceive(final Context context, final Intent intent) {
        ResultReturner.returnData(intent, out -> {
            var events = new LinkedBlockingQueue<String>();

            var receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent event) {
                    var action = event.getAction();
                    if (action == null) {
                        return;
                    }
                    switch (action) {
                        case Intent.ACTION_SCREEN_ON -> events.offer("{\"screen\":\"on\"}");
                        case Intent.ACTION_SCREEN_OFF -> events.offer("{\"screen\":\"off\"}");
                        // Unlocked, which is when a user is actually present rather than
                        // the screen merely being lit by a notification.
                        case Intent.ACTION_USER_PRESENT ->
                            events.offer("{\"screen\":\"unlocked\"}");
                        default -> { }
                    }
                }
            };

            var filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            // These three are broadcast to registered receivers only; a manifest
            // declaration would never fire, which is why this is a streaming API.
            context.registerReceiver(receiver, filter);
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
}
