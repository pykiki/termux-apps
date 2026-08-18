package com.termux.app.api;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionInfo;
import android.util.Log;

import androidx.annotation.RequiresPermission;


import java.io.PrintWriter;
import java.util.ArrayList;

public class SmsSendAPI {

    private static final String LOG_TAG = "SmsSendAPI";

    public static void onReceive(Context context, final Intent intent) {
        ResultReturner.returnData(intent, new ResultReturner.WithStringInput() {
            @RequiresPermission(allOf = {Manifest.permission.READ_PHONE_STATE, Manifest.permission.SEND_SMS})
            @Override
            public void writeResult(PrintWriter out) {
                final SmsManager smsManager = getSmsManager(context, intent);
                if (smsManager == null) {
                    // Returning quietly here reported success to the caller while nothing
                    // was sent - measured on a single-SIM device asking for slot 1.
                    out.println("{\"API_ERROR\":\"No SIM in slot "
                        + intent.getIntExtra("slot", -1) + ". Active slots: "
                        + activeSlots(context) + "\"}");
                    return;
                }

                String[] recipients = intent.getStringArrayExtra("recipients");

                if (recipients == null) {
                    // Used by old versions of termux-send-sms.
                    String recipient = intent.getStringExtra("recipient");
                    if (recipient != null) recipients = new String[]{recipient};
                }

                if (recipients == null || recipients.length == 0) {
                    out.println("{\"API_ERROR\":\"No recipient given\"}");
                } else {
                    final ArrayList<String> messages = smsManager.divideMessage(inputString);
                    for (String recipient : recipients) {
                        smsManager.sendMultipartTextMessage(recipient, null, messages, null, null);
                    }
                }
            }
        });
    }

    /**
     * The slots that actually hold a SIM, so a caller naming a missing one is told what it
     * could have asked for rather than left guessing.
     */
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    static String activeSlots(Context context) {
        try {
            var sm = context.getSystemService(SubscriptionManager.class);
            var list = sm.getActiveSubscriptionInfoList();
            if (list == null || list.isEmpty()) return "none";
            var slots = new StringBuilder();
            for (SubscriptionInfo si : list) {
                if (slots.length() > 0) slots.append(", ");
                slots.append(si.getSimSlotIndex());
            }
            return slots.toString();
        } catch (SecurityException e) {
            return "unknown without READ_PHONE_STATE";
        }
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    static SmsManager getSmsManager(Context context, final Intent intent) {
        int slot = intent.getIntExtra("slot", -1);
        if (slot == -1) {
            return SmsManager.getDefault();
        } else {
            SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
            var active = sm.getActiveSubscriptionInfoList();
            if (active == null) return null;
            for (SubscriptionInfo si : active) {
                if (si.getSimSlotIndex() == slot) {
                    return SmsManager.getSmsManagerForSubscriptionId(si.getSubscriptionId());
                }
            }
            Log.e(LOG_TAG, "Sim slot " + slot + " not found");
            return null;
        }
    }

}
