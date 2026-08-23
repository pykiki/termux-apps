package com.termux.app.api;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.util.Log;
import android.view.Display;

import com.termux.app.TermuxConstants;

/**
 * Starts another app's exported activity from the app's own context, and
 * lists the displays a start can target. `am start` runs in a shell process
 * with no visible window, so since Android 10 the platform silently drops
 * it; issued from the app's context this is the call a launcher makes, and
 * shells cannot read dumpsys, so the display list lives here too.
 */
public class ActivityStartAPI {

    public static void onReceive(final Context context, final Intent intent) {
        DisplayManager displayManager = context.getSystemService(DisplayManager.class);

        if ("displays".equals(intent.getStringExtra("query"))) {
            StringBuilder sb = new StringBuilder("[");
            for (Display d : displayManager.getDisplays()) {
                if (sb.length() > 1) sb.append(",");
                sb.append("{\"id\":").append(d.getDisplayId())
                  .append(",\"name\":\"")
                  .append(String.valueOf(d.getName()).replace('"', '\''))
                  .append("\"}");
            }
            sb.append("]");
            ResultReturner.returnData(intent, out -> out.println(sb));
            return;
        }

        String pkg = intent.getStringExtra("package");
        String component = intent.getStringExtra("component");
        Intent launch;
        if (component != null && !component.isEmpty()) {
            ComponentName name = ComponentName.unflattenFromString(component);
            if (name == null) {
                ResultReturner.returnData(intent, out -> out.println(
                    "{\"API_ERROR\":\"Malformed component, expected package/class\"}"));
                return;
            }
            launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LAUNCHER);
            launch.setComponent(name);
        } else if (pkg != null && !pkg.isEmpty()) {
            // The canonical "open this app" intent, exactly as a launcher builds it.
            launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) {
                ResultReturner.returnData(intent, out -> out.println(
                    "{\"API_ERROR\":\"No launchable activity in " + pkg + "\"}"));
                return;
            }
        } else {
            ResultReturner.returnData(intent, out -> out.println(
                "{\"API_ERROR\":\"Missing package or component extra\"}"));
            return;
        }
        String action = intent.getStringExtra("action");
        if (action != null && !action.isEmpty()) {
            launch.setAction(action);
        }
        String data = intent.getStringExtra("data");
        if (data != null && !data.isEmpty()) {
            launch.setData(Uri.parse(data));
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
        String display = intent.getStringExtra("display");
        if (display != null && !display.isEmpty()) {
            int displayId = -1;
            if ("external".equals(display)) {
                // First non-default display; absent one, the start falls
                // through to the default rather than failing.
                for (Display d : displayManager.getDisplays()) {
                    if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                        displayId = d.getDisplayId();
                        break;
                    }
                }
            } else {
                try {
                    displayId = Integer.parseInt(display);
                } catch (NumberFormatException ignored) {
                    ResultReturner.returnData(intent, out -> out.println(
                        "{\"API_ERROR\":\"display must be a number or 'external'\"}"));
                    return;
                }
            }
            if (displayId >= 0) {
                options.setLaunchDisplayId(displayId);
            }
        }
        try {
            Log.i(TermuxConstants.LOG_TAG, "ActivityStartAPI: startActivity " + launch);
            context.startActivity(launch, options.toBundle());
            ResultReturner.noteDone(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.e(TermuxConstants.LOG_TAG, "ActivityStartAPI: refused", e);
            ResultReturner.returnData(intent, out -> out.println(
                "{\"API_ERROR\":\"" + String.valueOf(e.getMessage()).replace('"', '\'') + "\"}"));
        }
    }
}
