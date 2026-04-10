package com.gigubi.app;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GigUReaderService extends AccessibilityService {
    private static final String TAG = "GigUReader";

    // Regexes ultra-robustas para capturar ofertas dinâmicas
    private static final Pattern PRICE_PATTERN = Pattern.compile("R\\$?\\s?(\\d+(?:[.,]\\d+)?)");
    private static final Pattern DISTANCE_PATTERN = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(km|m)", Pattern.CASE_INSENSITIVE);

    private double tempMaxPrice = 0;
    private double tempMaxKm = 0;
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

        // Inicia scan limpo para este evento/tela
        tempMaxPrice = 0;
        tempMaxKm = 0;

        processNode(rootNode);
        rootNode.recycle();

        // Se após ler a tela toda temos valores válidos, emitimos
        if (tempMaxPrice > 0 && tempMaxKm > 0) {
            emitOffer(tempMaxPrice, tempMaxKm);
        }
    }

    private void processNode(AccessibilityNodeInfo node) {
        if (node == null) return;

        if (node.getText() != null) {
            String text = node.getText().toString();
            
            // Limpa o texto de parênteses e ruídos comuns antes de testar
            String cleanText = text.replaceAll("[()\\t\\n\\r]", " ");
            extractData(cleanText);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            processNode(child);
            if (child != null) child.recycle();
        }
    }

    private void extractData(String text) {
        // Tenta capturar Preço - Pega o maior da tela (evita taxas menores)
        Matcher priceMatcher = PRICE_PATTERN.matcher(text);
        if (priceMatcher.find()) {
            double p = parseDouble(priceMatcher.group(1));
            if (p > tempMaxPrice) {
                tempMaxPrice = p;
            }
        }

        // Tenta capturar Distância - Pega a maior da tela (ignora a retirada, foca na entrega total)
        Matcher distMatcher = DISTANCE_PATTERN.matcher(text);
        if (distMatcher.find()) {
            double d = parseDouble(distMatcher.group(1));
            String unit = distMatcher.group(2).toLowerCase();
            double kmValue = unit.equals("m") ? d / 1000.0 : d;

            if (kmValue > tempMaxKm) {
                tempMaxKm = kmValue;
            }
        }
    }

    private void emitOffer(double price, double km) {
        // Evita disparar repetidamente para a mesma oferta no mesmo segundo
        if (System.currentTimeMillis() - lastCaptureTime < 2000) return;

        Log.d(TAG, "OFERTA DETECTADA: R$ " + price + " em " + km + " km");
        GigUPlugin plugin = GigUPlugin.getInstance();
        if (plugin != null) {
            plugin.emitOfferReceived("Offer Detected", price, km);
            lastCaptureTime = System.currentTimeMillis();
        }
    }

    private double parseDouble(String value) {
        try {
            // Remove pontos de milhar e troca vírgula por ponto decimal
            String cleanView = value.replace(".", "").replace(",", ".");
            return Double.parseDouble(cleanView);
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
