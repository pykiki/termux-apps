package com.termux.app.api;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.app.TermuxConstants;

import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Draws a centered panel of text over whatever is on a chosen display, and
 * takes it away again. A script narrating a long job has only the notification
 * shade to speak through, and a desktop mode such as Samsung DeX renders a
 * fraction of it - one line of the tray entry, no toasts at all. An overlay is
 * drawn by the app itself, so it appears wherever it is put, including on a
 * connected screen. It never takes focus or touch, and expires on its own so a
 * caller that dies cannot leave the screen covered.
 */
public class OverlayAPI {

    private static final String LOG_TAG = "OverlayAPI";

    /** Long enough for a desktop to start, short enough to forgive a crash. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * The live overlay, or null. It outlives the call that showed it - that is
     * what makes an update an update - and every access is on the main thread.
     */
    private static Panel panel;

    private static class Panel {
        WindowManager windowManager;
        LinearLayout view;
        TextView title;
        TextView body;
        int displayId;
        Runnable expiry;
    }

    public static void onReceive(final Context context, final Intent intent) {
        if ("dismiss".equals(intent.getStringExtra("action"))) {
            MAIN.post(OverlayAPI::dismiss);
            ResultReturner.noteDone(intent);
            return;
        }

        // Declaring the permission is not holding it: until the user turns
        // "Appear on top" on, addView throws and the caller sees nothing.
        if (!Settings.canDrawOverlays(context)) {
            ResultReturner.returnData(intent, out -> out.println("{\"API_ERROR\":\"Appear on top "
                + "is not granted - Settings > Apps > Termux > Appear on top\"}"));
            return;
        }

        ResultReturner.returnData(intent, new ResultReturner.WithStringInput() {
            @Override
            public void writeResult(PrintWriter out) {
                String text = intent.getStringExtra("text");
                if (text == null || text.isEmpty()) text = inputString;
                if (text == null || text.isEmpty()) {
                    out.println("{\"API_ERROR\":\"Nothing to show - pass text or pipe it in\"}");
                    return;
                }
                final String body = text;
                final String title = intent.getStringExtra("title");
                final int displayId = resolveDisplay(context, intent.getStringExtra("display"));
                int seconds = intent.getIntExtra("timeout", DEFAULT_TIMEOUT_SECONDS);
                final long timeoutMillis = seconds > 0 ? seconds * 1000L : 0L;

                // Windows belong to the main thread and this writer does not
                // run on it; the caller waits, so a refusal reaches it.
                final StringBuilder failure = new StringBuilder();
                final CountDownLatch done = new CountDownLatch(1);
                MAIN.post(() -> {
                    try {
                        show(context, body, title, displayId, timeoutMillis);
                    } catch (Exception e) {
                        Log.e(TermuxConstants.LOG_TAG, LOG_TAG + ": refused", e);
                        failure.append(String.valueOf(e.getMessage()).replace('"', '\''));
                    } finally {
                        done.countDown();
                    }
                });
                try {
                    done.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (failure.length() > 0) out.println("{\"API_ERROR\":\"" + failure + "\"}");
            }
        });
    }

    /**
     * Display id for the "display" extra: an id, "external" for the first
     * screen that is not the built-in one, or the built-in one by default.
     */
    private static int resolveDisplay(Context context, String display) {
        if (display == null || display.isEmpty()) return Display.DEFAULT_DISPLAY;
        DisplayManager displayManager = context.getSystemService(DisplayManager.class);
        if ("external".equals(display)) {
            for (Display d : displayManager.getDisplays()) {
                if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return d.getDisplayId();
            }
            return Display.DEFAULT_DISPLAY;
        }
        try {
            return Integer.parseInt(display);
        } catch (NumberFormatException ignored) {
            return Display.DEFAULT_DISPLAY;
        }
    }

    private static void show(Context context, String body, String title, int displayId,
                             long timeoutMillis) {
        // A window cannot move between displays: the old one goes first.
        if (panel != null && panel.displayId != displayId) dismiss();

        if (panel == null) {
            DisplayManager displayManager = context.getSystemService(DisplayManager.class);
            Display display = displayManager.getDisplay(displayId);
            if (display == null) throw new IllegalStateException("No display " + displayId);

            // A window on another screen needs a context bound to it; the
            // window context carries both the display and the window type.
            Context windowContext = context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);

            float density = windowContext.getResources().getDisplayMetrics().density;
            int pad = Math.round(24 * density);

            GradientDrawable background = new GradientDrawable();
            background.setColor(0xE6101014);
            background.setCornerRadius(18 * density);

            LinearLayout view = new LinearLayout(windowContext);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(pad, pad, pad, pad);
            view.setBackground(background);

            TextView titleView = new TextView(windowContext);
            titleView.setTextColor(Color.WHITE);
            titleView.setTypeface(null, Typeface.BOLD);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            titleView.setPadding(0, 0, 0, Math.round(8 * density));
            view.addView(titleView);

            TextView bodyView = new TextView(windowContext);
            bodyView.setTextColor(0xFFE0E0E0);
            bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            view.addView(bodyView);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.CENTER;

            WindowManager windowManager = windowContext.getSystemService(WindowManager.class);
            windowManager.addView(view, params);

            panel = new Panel();
            panel.windowManager = windowManager;
            panel.view = view;
            panel.title = titleView;
            panel.body = bodyView;
            panel.displayId = displayId;
            Log.i(TermuxConstants.LOG_TAG, LOG_TAG + ": shown on display " + displayId);
        }

        panel.title.setText(title == null ? "" : title);
        panel.title.setVisibility(title == null || title.isEmpty()
            ? TextView.GONE : TextView.VISIBLE);
        panel.body.setText(body);

        if (panel.expiry != null) MAIN.removeCallbacks(panel.expiry);
        panel.expiry = null;
        if (timeoutMillis > 0) {
            panel.expiry = OverlayAPI::dismiss;
            MAIN.postDelayed(panel.expiry, timeoutMillis);
        }
    }

    private static void dismiss() {
        if (panel == null) return;
        if (panel.expiry != null) MAIN.removeCallbacks(panel.expiry);
        try {
            panel.windowManager.removeView(panel.view);
        } catch (IllegalArgumentException e) {
            // The window was already gone - the display was disconnected.
            Log.i(TermuxConstants.LOG_TAG, LOG_TAG + ": already removed");
        }
        panel = null;
    }
}
