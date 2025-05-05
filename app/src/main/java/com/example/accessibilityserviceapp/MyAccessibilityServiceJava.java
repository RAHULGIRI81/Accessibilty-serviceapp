package com.example.accessibilityserviceapp;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class MyAccessibilityServiceJava extends AccessibilityService {

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null) {
            int eventType = event.getEventType();
            String eventText = null;
            switch (eventType) {
                case AccessibilityEvent.TYPE_VIEW_CLICKED:
                    eventText = "Clicked: ";
                    break;
                case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                    eventText = "Focused: ";
                    break;
            }
            if (eventText != null) {
                String contentDescription = event.getContentDescription() != null
                        ? event.getContentDescription().toString()
                        : "No description";
                Log.d("AccessibilityService", eventText + contentDescription);
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.d("AccessibilityService", "Service interrupted");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d("AccessibilityService", "Service connected");
    }
}