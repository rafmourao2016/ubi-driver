package com.gigubi.app;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GigUReaderService extends AccessibilityService {
    private static final String TAG = "GigUReader";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        // Monitor only Uber and 99
        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!packageName.contains("ubercab") && !packageName.contains("taxis99") && !packageName.contains("noventaenove")) {
            return;
        }

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        processNode(rootNode);
        rootNode.recycle();
    }

    private boolean processNode(AccessibilityNodeInfo node) {
        if (node == null) return false;

        boolean foundBoth = false;
        if (node.getText() != null) {
            String text = node.getText().toString();
            foundBoth = checkAndEmit(text);
        }

        if (foundBoth) return true;

        for (int i = 0; i < node.getChildCount(); i++) {
            if (processNode(node.getChild(i))) return true;
        }
        return false;
    }

    private double currentPrice = 0;
    private double currentKm = 0;

    private boolean checkAndEmit(String text) {
        // Regex for Price: R$ 12,34
        Pattern pricePattern = Pattern.compile("R\\$\\s?(\\d+[.,]\\d+)");
        // Regex for Distance: 5,2 km
        Pattern kmPattern = Pattern.compile("(\\d+[.,]\\d+)\\s?km");

        Matcher priceMatcher = pricePattern.matcher(text);
        Matcher kmMatcher = kmPattern.matcher(text);

        if (priceMatcher.find()) {
            currentPrice = parseDouble(priceMatcher.group(1));
        }

        if (kmMatcher.find()) {
            currentKm = parseDouble(kmMatcher.group(1));
        }

        if (currentPrice > 0 && currentKm > 0) {
            Log.d(TAG, "Oferta detectada completa: R$ " + currentPrice + " | " + currentKm + " km");
            GigUPlugin plugin = GigUPlugin.getInstance();
            if (plugin != null) {
                plugin.emitOfferReceived(text, currentPrice, currentKm);
            }
            // Reset for next event
            currentPrice = 0;
            currentKm = 0;
            return true;
        }
        return false;
    }

    private double parseDouble(String value) {
        try {
            return Double.parseDouble(value.replace(",", "."));
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Serviço interrompido");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "GigU Accessibility Service conectado!");
    }
}
