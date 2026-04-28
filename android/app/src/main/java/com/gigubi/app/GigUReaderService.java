package com.gigubi.app;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
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

    // Lista temporária de km coletados em UM evento (para somar trecho de ida + corrida)
    private final List<Double> eventKmList = new ArrayList<>();

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

        // Escaneia a árvore de nós para este evento
        eventKmList.clear();
        processNode(rootNode);
        rootNode.recycle();

        // Soma TODOS os trechos de km encontrados na tela (ida ao passageiro + corrida)
        // IMPORTANTE: exige ao menos 2 distâncias — toda oferta real do 99 mostra
        // pickup km + trip km. Telas de home (ex: "99 Abastece") só geram 0 ou 1 km.
        if (eventKmList.size() >= 2) {
            double eventTotalKm = 0;
            for (double km : eventKmList) eventTotalKm += km;
            if (eventTotalKm > accumKm) {
                Log.d(TAG, "  -> Km total (soma de " + eventKmList.size() + " trechos): " + eventTotalKm + " km");
                accumKm = eventTotalKm;
            }
        } else if (!eventKmList.isEmpty()) {
            Log.d(TAG, "  [IGNORADO] Só " + eventKmList.size() + " km na tela — provável tela home/idle");
        }

        Log.d(TAG, "Estado acumulado — Preço: " + accumPrice + " | Km total: " + accumKm);

        // Emite quando temos ambos os valores e o throttle permite
        if (accumPrice > 0 && accumKm > 0 && (now - lastEmitTime) > EMIT_THROTTLE_MS) {
            emitOffer(accumPrice, accumKm);
            resetIdleTimer(); // reinicia o contador de inatividade
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
                // Reseta acumulador
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

        // Coleta cada trecho de km separadamente na lista do evento
        // (ida ao passageiro + distância da corrida = distância total rodada)
        Matcher distMatcher = DISTANCE_PATTERN.matcher(text);
        while (distMatcher.find()) {
            double d = parseDouble(distMatcher.group(1));
            String unit = distMatcher.group(2).toLowerCase().trim();
            double kmValue = unit.equals("m") ? d / 1000.0 : d;
            // Ignora distâncias absurdas (< 200m ou > 100km)
            if (kmValue > 0.2 && kmValue < 100) {
                // Evita duplicatas exatas na mesma varredura
                if (!eventKmList.contains(kmValue)) {
                    Log.d(TAG, "  -> Km parcial: " + kmValue + " km");
                    eventKmList.add(kmValue);
                }
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
