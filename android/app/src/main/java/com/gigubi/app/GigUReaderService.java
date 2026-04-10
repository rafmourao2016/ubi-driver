package com.gigubi.app;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GigUReaderService extends AccessibilityService {
    private static final String TAG = "GigUReader";

    // Regexes mais robustas para capturar ofertas dinâmicas
    private static final Pattern PRICE_PATTERN = Pattern.compile("R\\$\\s?(\\d+(?:[.,]\\d+)?)");
    private static final Pattern DISTANCE_PATTERN = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s?(km|m)", Pattern.CASE_INSENSITIVE);

    private double currentPrice = 0;
    private double currentKm = 0;
    private long lastCaptureTime = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!packageName.contains("ubercab") && !packageName.contains("taxis99") && !packageName.contains("noventaenove")) {
            return;
        }

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        // Resetar se passar muito tempo entre eventos (nova oferta provável)
        if (System.currentTimeMillis() - lastCaptureTime > 5000) {
            currentPrice = 0;
            currentKm = 0;
        }

        processNode(rootNode);
        rootNode.recycle();
    }

    private void processNode(AccessibilityNodeInfo node) {
        if (node == null) return;

        if (node.getText() != null) {
            String text = node.getText().toString();
            extractData(text);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            processNode(node.getChild(i));
        }

        // Se capturamos ambos, emitimos e limpamos
        if (currentPrice > 0 && currentKm > 0) {
            emitOffer();
        }
    }

    private void extractData(String text) {
        // Tenta capturar Preço
        Matcher priceMatcher = PRICE_PATTERN.matcher(text);
        if (priceMatcher.find()) {
            double p = parseDouble(priceMatcher.group(1));
            if (p > 0) {
                currentPrice = p;
                lastCaptureTime = System.currentTimeMillis();
                Log.d(TAG, "Preço capturado: R$ " + currentPrice);
            }
        }

        // Tenta capturar Distância (KM ou M)
        Matcher distMatcher = DISTANCE_PATTERN.matcher(text);
        if (distMatcher.find()) {
            double d = parseDouble(distMatcher.group(1));
            String unit = distMatcher.group(2).toLowerCase();

            if (d > 0) {
                // Se for metros, converte para KM (ex: 500m -> 0.5km)
                if (unit.equals("m")) {
                    currentKm = d / 1000.0;
                } else {
                    currentKm = d;
                }
                lastCaptureTime = System.currentTimeMillis();
                Log.d(TAG, "Distância capturada: " + currentKm + " km (" + unit + ")");
            }
        }
    }

    private void emitOffer() {
        Log.d(TAG, "OFERTA COMPLETA: R$ " + currentPrice + " em " + currentKm + " km");
        GigUPlugin plugin = GigUPlugin.getInstance();
        if (plugin != null) {
            plugin.emitOfferReceived("Offer Detected", currentPrice, currentKm);
        }
        // Limpar para evitar duplicidade no mesmo evento
        currentPrice = 0;
        currentKm = 0;
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
