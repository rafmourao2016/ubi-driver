package com.gigubi.app;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GigUReaderService extends AccessibilityService {
    private static final String TAG = "GigUReader";

    // Janela de 5s para acumular preço e distância de eventos diferentes
    private static final long ACCUMULATION_WINDOW_MS = 5000;
    // Throttle de 3s para não emitir a mesma oferta várias vezes
    private static final long EMIT_THROTTLE_MS = 3000;
    // Tempo sem oferta para limpar o overlay (30s)
    private static final long IDLE_CLEAR_MS = 30_000;

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
    private double accumKm   = 0;
    private long firstEventTime = 0;
    private long lastEmitTime   = 0;

    // Timer para limpar overlay quando motorista fica ocioso
    private Timer idleTimer = null;

    // Lista temporária de km coletados em UM ciclo de scan
    private final List<Double> eventKmList = new ArrayList<>();

    // Flag para saber o app atual
    private boolean currentAppIsUber = false;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        String packageName = event.getPackageName() != null
            ? event.getPackageName().toString() : "";

        boolean isUber = packageName.contains("ubercab");
        boolean is99   = packageName.equals("com.app99.driver")
                      || packageName.contains("taxis99")
                      || packageName.contains("noventaenove")
                      || packageName.contains("com.ninety9")
                      || packageName.contains("99app");

        if (!isUber && !is99) return;

        // Mantém flag do app atual para uso posterior
        if (isUber) currentAppIsUber = true;
        if (is99)   currentAppIsUber = false;

        Log.d(TAG, "Evento: " + packageName + " | tipo: " + event.getEventType());

        // ============================================================
        // ESTRATÉGIA DUAL: escaneia TODAS as janelas (fix Uber bottom sheet)
        // A oferta da Uber aparece como uma janela dialog separada que
        // getRootInActiveWindow() NÃO captura. getWindows() pega todas.
        // ============================================================
        long now = System.currentTimeMillis();
        if (now - firstEventTime > ACCUMULATION_WINDOW_MS) {
            Log.d(TAG, "[RESET] Nova janela de acumulação");
            accumPrice = 0;
            accumKm = 0;
            firstEventTime = now;
        }

        eventKmList.clear();

        // Tenta escanear todas as janelas na tela
        boolean scannedAny = false;
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null && !windows.isEmpty()) {
                for (AccessibilityWindowInfo window : windows) {
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null) {
                        processNode(root);
                        root.recycle();
                        scannedAny = true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getWindows() falhou: " + e.getMessage());
        }

        // Fallback: se não conseguiu nenhuma janela, usa o método antigo
        if (!scannedAny) {
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode != null) {
                processNode(rootNode);
                rootNode.recycle();
            } else {
                Log.w(TAG, "Nenhuma janela acessível");
                return;
            }
        }

        // Decide quantos trechos de km exige:
        // Uber: 1 (a oferta pode aparecer com 1 ou 2 trechos)
        // 99:   2 (evita o painel "99 Abastece" que só tem 1 ou nenhum km)
        int minKmRequired = currentAppIsUber ? 1 : 2;

        if (eventKmList.size() >= minKmRequired) {
            double eventTotalKm = 0;
            for (double km : eventKmList) eventTotalKm += km;
            if (eventTotalKm > accumKm) {
                Log.d(TAG, "  -> Km total (" + eventKmList.size() + " trechos): " + eventTotalKm + " km");
                accumKm = eventTotalKm;
            }
        } else if (!eventKmList.isEmpty()) {
            Log.d(TAG, "  [IGNORADO] " + eventKmList.size() + " km na tela — aguardando mais trechos ou tela idle");
        }

        Log.d(TAG, "Acumulado — Preço: R$" + accumPrice + " | Km: " + accumKm);

        if (accumPrice > 0 && accumKm > 0 && (now - lastEmitTime) > EMIT_THROTTLE_MS) {
            emitOffer(accumPrice, accumKm);
            resetIdleTimer();
        }
    }

    /** Reinicia o timer que limpa o overlay após 30s sem oferta */
    private void resetIdleTimer() {
        if (idleTimer != null) idleTimer.cancel();
        idleTimer = new Timer();
        idleTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Log.d(TAG, "[IDLE] 30s sem oferta — limpando overlay");
                OverlayPlugin overlay = OverlayPlugin.getInstance();
                if (overlay != null) overlay.clearOverlay();
                accumPrice = 0;
                accumKm = 0;
                firstEventTime = 0;
            }
        }, IDLE_CLEAR_MS);
    }

    private void processNode(AccessibilityNodeInfo node) {
        if (node == null) return;

        CharSequence textCS = node.getText();
        CharSequence descCS = node.getContentDescription();

        if (textCS != null) {
            // Mantém parênteses pois a Uber usa "(2.0 km)" — apenas limpa tabulações e quebras
            String clean = textCS.toString().replaceAll("[\t\n\r]", " ");
            Log.v(TAG, "  txt: " + clean);
            extractData(clean);
        }
        if (descCS != null) {
            String clean = descCS.toString().replaceAll("[\t\n\r]", " ");
            Log.v(TAG, "  dsc: " + clean);
            extractData(clean);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            processNode(child);
            if (child != null) child.recycle();
        }
    }

    private void extractData(String text) {
        // Captura preços: R$ 13,68 ou R$13.68
        Matcher priceMatcher = PRICE_PATTERN.matcher(text);
        while (priceMatcher.find()) {
            double p = parseDouble(priceMatcher.group(1));
            // Faixa válida de corrida: R$1 até R$500
            if (p > 1 && p < 500 && p > accumPrice) {
                Log.d(TAG, "  -> Preço: R$" + p);
                accumPrice = p;
            }
        }

        // Captura distâncias: "2.0 km", "1,9 km", "500 m", etc.
        // IMPORTANTE: regex não remove parênteses para capturar "(2.0 km)"
        Matcher distMatcher = DISTANCE_PATTERN.matcher(text);
        while (distMatcher.find()) {
            double d = parseDouble(distMatcher.group(1));
            String unit = distMatcher.group(2).toLowerCase().trim();
            double kmValue = unit.equals("m") ? d / 1000.0 : d;
            // Faixa válida: 200m até 100km
            if (kmValue > 0.2 && kmValue < 100) {
                if (!eventKmList.contains(kmValue)) {
                    Log.d(TAG, "  -> Km parcial: " + kmValue + " km");
                    eventKmList.add(kmValue);
                }
            }
        }
    }

    private void emitOffer(double price, double km) {
        Log.i(TAG, ">>> OFERTA: R$" + price + " | " + km + " km");
        GigUPlugin plugin = GigUPlugin.getInstance();
        if (plugin != null) {
            plugin.emitOfferReceived("Offer Detected", price, km);
            lastEmitTime = System.currentTimeMillis();
            accumPrice = 0;
            accumKm = 0;
            firstEventTime = 0;
        } else {
            Log.e(TAG, "GigUPlugin null — WebView em background!");
        }
    }

    private double parseDouble(String value) {
        try {
            value = value.replace("\u00A0", "").trim();
            if (value.contains(",")) {
                // Formato BR: 1.234,56 → remove ponto de milhar, converte vírgula
                value = value.replace(".", "").replace(",", ".");
            }
            // Formato Uber: 2.0 → já está ok para parseDouble
            return Double.parseDouble(value);
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
        Log.i(TAG, "=== GigU Accessibility Service CONECTADO ===");
    }
}
