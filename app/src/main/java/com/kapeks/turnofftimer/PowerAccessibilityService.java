package com.kapeks.turnofftimer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.app.NotificationCompat;

public class PowerAccessibilityService extends AccessibilityService {
    private static final String TAG = "PowerAccessService";
    private static PowerAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "Accessibility Service Connected");

        // Force foreground to prevent Chinese OS from killing the accessibility service
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        "AccessChannel", "Erişilebilirlik", NotificationManager.IMPORTANCE_MIN);
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) manager.createNotificationChannel(channel);
            }

            Intent notificationIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new NotificationCompat.Builder(this, "AccessChannel")
                    .setContentTitle("TV Kapanma Sistemi Aktif")
                    .setContentText("Kapatma menüsü beklentisi...")
                    .setSmallIcon(android.R.drawable.ic_lock_power_off)
                    .setContentIntent(pendingIntent)
                    .build();

            startForeground(2, notification);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground for accessibility", e);
        }
    }

    public static PowerAccessibilityService getInstance() {
        return instance;
    }

    private boolean isShutdownTriggered = false;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isShutdownTriggered) return;

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                findAndClickPowerOff(root);
            }
        }
    }

    private void findAndClickPowerOff(AccessibilityNodeInfo node) {
        if (node == null) return;
        
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        
        boolean match = false;
        if (text != null) {
            String lowerText = text.toString().toLowerCase();
            if (lowerText.contains("kapat") || lowerText.contains("power off") || lowerText.contains("shut down") || lowerText.equals("off") || lowerText.equals("kapatma")) {
                match = true;
            }
        }
        if (!match && desc != null) {
            String lowerDesc = desc.toString().toLowerCase();
            if (lowerDesc.contains("kapat") || lowerDesc.contains("power off") || lowerDesc.contains("shut down") || lowerDesc.equals("off") || lowerDesc.equals("kapatma")) {
                match = true;
            }
        }

        if (match) {
            Log.d(TAG, "Found target button. Attempting Gesture Tap...");
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            
            if (!bounds.isEmpty()) {
                int x = bounds.centerX();
                int y = bounds.centerY();
                
                Path path = new Path();
                path.moveTo(x, y);
                GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
                gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
                
                boolean dispatched = dispatchGesture(gestureBuilder.build(), null, null);
                Log.d(TAG, "Gesture tapped at " + x + ", " + y + " - Dispatched: " + dispatched);
                isShutdownTriggered = false; // Reset after click
                return;
            } else {
                Log.d(TAG, "Bounds empty, trying ACTION_CLICK as fallback");
                if (node.isClickable()) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    isShutdownTriggered = false;
                    return;
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            findAndClickPowerOff(node.getChild(i));
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    public void triggerPowerDialog() {
        Log.d(TAG, "Triggering Power Dialog");
        isShutdownTriggered = true;
        performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }
}
