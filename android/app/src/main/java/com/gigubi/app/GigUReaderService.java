package com.gigubi.app;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GigUReaderService extends AccessibilityService {
    private static final String TAG = "GigUReader";

    // Janela de 5s para acumular preço e distância de eventos diferentes
    private static final long ACCUMULATION_WINDOW_MS = 5000;
    // Throttle de 3s para não emitir a mesma oferta várias vezes
    private static final long EMIT_THROTTLE_MS = 3000;

    // Regex robusto: aceita espaço normal e não-quebrável (\u00A0) que a Uber usa no BR
    private static final Pattern PRICE_PATTERN = Pattern.compile(
        "R\\$[\\s\u00A0]*(\\d+(?:[.,]\\d+)?)"
    );
    private static final Pattern DISTANCE_PATTERN = Pattern.compile(
        "(\\d+(?:[.,]\\d+)?)[\\s\u00A0]*(km|m)\\b",
        Pattern.CASE_INSENSITIVE
    );

    // Valores acumulados entre eventos
    private double accumPrice = 0;
    private double accumKm = 0;
    private long firstEventTime = 0; // início da janela de acumulação
    private long lastEmitTime = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        String packageName = event.getPackageName() != null
            ? event.getPackageName().toString() : "";

        // === LOG DIAGNÓSTICO: mostra TODOS os pacotes no Logcat ===
        // Use: adb logcat -s GigUReader para monitorar
        // Depois de confirmar o pacote do 99, pode remover esta linha
        Log.d(TAG, "[DIAGNÓSTICO] Pacote ativo: " + packageName);

        // Aceita Uber Rider, Uber Driver e variantes do 99
        boolean isUber = packageName.contains("ubercab");
        boolean is99   = packageName.equals("com.app99.driver")   // 99 Motoristas (oficial)
                      || packageName.contains("taxis99")
                      || packageName.contains("noventaenove")
                      || packageName.contains("com.ninety9")
                      || packageName.contains("99app");

        if (!isUber && !is99) return;

        Log.d(TAG, "Evento de: " + packageName + " | tipo: " + event.getEventType());

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            Log.w(TAG, "getRootInActiveWindow() retornou null");
            return;
        }

        // Verifica se janela de acumulação expirou — se sim, reseta
        long now = System.currentTimeMillis();
        if (now - firstEventTime > ACCUMULATION_WINDOW_MS) {
            Log.d(TAG, "[RESET] Nova janela de acumulação iniciada");
            accumPrice = 0;
            accumKm = 0;
            firstEventTime = now;
        }

        // Escaneia a árvore de nós e acumula os maiores valores encontrados
        processNode(rootNode);
        rootNode.recycle();

        Log.d(TAG, "Estado acumulado — Preço: " + accumPrice + " | Km: " + accumKm);

        // Emite quando temos ambos os valores e o throttle permite
        if (accumPrice > 0 && accumKm > 0 && (now - lastEmitTime) > EMIT_THROTTLE_MS) {
            emitOffer(accumPrice, accumKm);
        }
    }

    private void processNode(AccessibilityNodeInfo node) {
        if (node == null) return;

        CharSequence textCS = node.getText();
        CharSequence descCS = node.getContentDescription();

        if (textCS != null) {
            String clean = textCS.toString().replaceAll("[()\t\n\r]", " ");
            Log.v(TAG, "  nó texto: " + clean);
            extractData(clean);
        }
        if (descCS != null) {
            String clean = descCS.toString().replaceAll("[()\t\n\r]", " ");
            Log.v(TAG, "  nó desc: " + clean);
            extractData(clean);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            processNode(child);
            if (child != null) child.recycle();
        }
    }

    private void extractData(String text) {
        Matcher priceMatcher = PRICE_PATTERN.matcher(text);
        while (priceMatcher.find()) {
            double p = parseDouble(priceMatcher.group(1));
            // Ignora valores absurdos (< R$1 ou > R$500 provavelmente não são corrida)
            if (p > 1 && p < 500 && p > accumPrice) {
                Log.d(TAG, "  -> Preço capturado: R$" + p);
                accumPrice = p;
            }
        }

        Matcher distMatcher = DISTANCE_PATTERN.matcher(text);
        while (distMatcher.find()) {
            double d = parseDouble(distMatcher.group(1));
            String unit = distMatcher.group(2).toLowerCase().trim();
            double kmValue = unit.equals("m") ? d / 1000.0 : d;
            // Ignora distâncias absurdas (< 200m ou > 100km)
            if (kmValue > 0.2 && kmValue < 100 && kmValue > accumKm) {
                Log.d(TAG, "  -> Distância capturada: " + kmValue + " km");
                accumKm = kmValue;
            }
        }
    }

    private void emitOffer(double price, double km) {
        Log.i(TAG, ">>> OFERTA EMITIDA: R$" + price + " | " + km + " km");
        GigUPlugin plugin = GigUPlugin.getInstance();
        if (plugin != null) {
            plugin.emitOfferReceived("Offer Detected", price, km);
            lastEmitTime = System.currentTimeMillis();
            // Reseta acumulador após emitir
            accumPrice = 0;
            accumKm = 0;
            firstEventTime = 0;
        } else {
            Log.e(TAG, "GigUPlugin.getInstance() é null — WebView pode estar em background!");
        }
    }

    private double parseDouble(String value) {
        try {
            return Double.parseDouble(
                value.replace("\u00A0", "").replace(".", "").replace(",", ".")
            );
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Serviço interrompido");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "=== GigU Accessibility Service CONECTADO e PRONTO ===");
    }
}
