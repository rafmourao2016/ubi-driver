package com.gigubi.app;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.getcapacitor.JSObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GigUReaderService extends AccessibilityService {
    private static final String TAG = "GigUReader";

    private static final long ACCUMULATION_WINDOW_MS = 5000;
    private static final long EMIT_THROTTLE_MS = 3000;
    private static final long IDLE_CLEAR_MS = 30_000;

    private static final Pattern PRICE_PATTERN = Pattern.compile(
        "R\\$[\\s\u00A0]*(\\d+(?:[.,]\\d+)?)"
    );
    private static final Pattern DISTANCE_PATTERN = Pattern.compile(
        "(\\d+(?:[.,]\\d+)?)[\\s\u00A0]*(km|m)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private double accumPrice = 0;
    private double accumKm   = 0;
    private long firstEventTime = 0;
    private long lastEmitTime   = 0;
    private Timer idleTimer = null;
    private boolean currentAppIsUber = false;

    private final List<Double> eventKmList = new ArrayList<>();

    // Rastreia pacotes já logados para não poluir o log (1 entrada por pacote por 5s)
    private final java.util.Map<String, Long> loggedPkgs = new java.util.HashMap<>();

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (pkg.isEmpty()) return;

        // ── Diagnóstico: loga TODOS os pacotes (1x por 5s) ──
        long nowLog = System.currentTimeMillis();
        Long lastLog = loggedPkgs.get(pkg);
        if (lastLog == null || nowLog - lastLog > 5000) {
            loggedPkgs.put(pkg, nowLog);
            notifyDiag("[APP] " + pkg);
            Log.d(TAG, "[PKG] " + pkg);
        }

        // ── Filtra só Uber e 99 (em código, não no XML) ──
        boolean isUber = pkg.contains("uber");
        boolean is99   = pkg.contains("99")
                      || pkg.contains("taxis")
                      || pkg.contains("noventaenove")
                      || pkg.contains("app99");

        if (!isUber && !is99) return;

        if (isUber) currentAppIsUber = true;
        if (is99)   currentAppIsUber = false;

        Log.d(TAG, "Evento: " + pkg + " | tipo: " + event.getEventType());
        notifyDiag("[✓ UBER/99] " + pkg + " tipo:" + event.getEventType());

        long now = System.currentTimeMillis();
        if (now - firstEventTime > ACCUMULATION_WINDOW_MS) {
            accumPrice = 0;
            accumKm = 0;
            firstEventTime = now;
        }

        eventKmList.clear();

        // ── 1) Janela do evento (via source node → raiz) ──
        AccessibilityNodeInfo eventRoot = getEventRoot(event);
        int eventWindowId = -1;
        if (eventRoot != null) {
            eventWindowId = eventRoot.getWindowId();
            processNode(eventRoot);
            eventRoot.recycle();
        }

        // ── 2) Fallback: janela ativa (quando source é null) ──
        if (eventRoot == null) {
            AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
            if (activeRoot != null) {
                eventWindowId = activeRoot.getWindowId();
                processNode(activeRoot);
                activeRoot.recycle();
            }
        }

        // ── 3) Janelas extras — SÓ TYPE_APPLICATION ──
        // TYPE_INPUT_METHOD = teclado, TYPE_SYSTEM = nossa overlay e barras do sistema
        // Só escaneamos janelas de app (evita ler nosso próprio overlay!)
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    int wType = window.getType();
                    // Só janelas de aplicativo — ignora sistema, teclado e overlays
                    if (wType != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    if (window.getId() == eventWindowId) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;
                    processNode(root);
                    root.recycle();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getWindows() erro: " + e.getMessage());
        }

        if (!eventKmList.isEmpty() || accumPrice > 0) {
            notifyDiag("[SCAN] preço=" + accumPrice + " kms=" + eventKmList);
        }


        // Regra anti-falso-positivo:
        // Uber: 1+ km (oferta pode ter só 1 trecho visível)
        // 99:   2+ km (evita "99 Abastece" que tem 0-1 km)
        int minKm = currentAppIsUber ? 1 : 2;

        if (eventKmList.size() >= minKm) {
            double total = 0;
            for (double k : eventKmList) total += k;
            if (total > accumKm) {
                Log.d(TAG, "  -> Km total: " + total + " (" + eventKmList.size() + " trechos)");
                accumKm = total;
            }
        } else if (!eventKmList.isEmpty()) {
            Log.d(TAG, "  [AGUARDANDO] " + eventKmList.size() + " km — esperando mais trechos");
        }

        Log.d(TAG, "Acumulado: R$" + accumPrice + " | " + accumKm + " km");

        if (accumPrice > 0 && accumKm > 0 && (now - lastEmitTime) > EMIT_THROTTLE_MS) {
            emitOffer(accumPrice, accumKm);
            resetIdleTimer();
        }
    }

    private void resetIdleTimer() {
        if (idleTimer != null) idleTimer.cancel();
        idleTimer = new Timer();
        idleTimer.schedule(new TimerTask() {
            @Override public void run() {
                Log.d(TAG, "[IDLE] 30s sem oferta");
                OverlayPlugin overlay = OverlayPlugin.getInstance();
                if (overlay != null) overlay.clearOverlay();
                accumPrice = 0; accumKm = 0; firstEventTime = 0;
            }
        }, IDLE_CLEAR_MS);
    }

    private void processNode(AccessibilityNodeInfo node) {
        if (node == null) return;

        // ── Regra de Ouro: NUNCA ler nosso próprio app/overlay ──
        CharSequence pkg = node.getPackageName();
        if (pkg != null && pkg.toString().contains("com.gigubi.app")) {
            return;
        }

        CharSequence txt = node.getText();
        CharSequence dsc = node.getContentDescription();
        if (txt != null) extractData(txt.toString().replaceAll("[\t\n\r]", " "));
        if (dsc != null) extractData(dsc.toString().replaceAll("[\t\n\r]", " "));
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            processNode(child);
            if (child != null) child.recycle();
        }
    }

    private void extractData(String text) {
        Matcher pm = PRICE_PATTERN.matcher(text);
        while (pm.find()) {
            double p = parseDouble(pm.group(1));
            if (p > 1 && p < 500 && p > accumPrice) {
                Log.d(TAG, "  -> Preço: R$" + p);
                accumPrice = p;
            }
        }
        Matcher dm = DISTANCE_PATTERN.matcher(text);
        while (dm.find()) {
            double d = parseDouble(dm.group(1));
            String unit = dm.group(2).toLowerCase().trim();
            double km = unit.equals("m") ? d / 1000.0 : d;
            if (km > 0.2 && km < 100 && !eventKmList.contains(km)) {
                Log.d(TAG, "  -> Km parcial: " + km);
                eventKmList.add(km);
            }
        }
    }

    private void emitOffer(double price, double km) {
        Log.i(TAG, ">>> EMITINDO: R$" + price + " | " + km + " km");
        GigUPlugin plugin = GigUPlugin.getInstance();
        if (plugin != null) {
            plugin.emitOfferReceived("Offer", price, km);
            lastEmitTime = System.currentTimeMillis();
            accumPrice = 0; accumKm = 0; firstEventTime = 0;
        } else {
            Log.e(TAG, "GigUPlugin null!");
            notifyDiag("[LEITOR] GigUPlugin null — WebView em background?");
        }
    }

    /** Emite log de diagnóstico para o UI do app */
    private void notifyDiag(String msg) {
        GigUPlugin plugin = GigUPlugin.getInstance();
        if (plugin == null) return;
        plugin.sendDiagLog(msg);
    }

    /**
     * Navega de event.getSource() até a raiz da árvore de acessibilidade.
     * Mais confiável que getRootInActiveWindow() para janelas flutuantes (Uber bottom sheet).
     */
    private AccessibilityNodeInfo getEventRoot(AccessibilityEvent event) {
        try {
            AccessibilityNodeInfo source = event.getSource();
            if (source == null) return null;
            // Sobe na árvore até encontrar o nó raiz
            AccessibilityNodeInfo current = source;
            AccessibilityNodeInfo parent = current.getParent();
            while (parent != null) {
                current.recycle();
                current = parent;
                parent = current.getParent();
            }
            return current; // retorna o nó raiz (não reciclado)
        } catch (Exception e) {
            Log.w(TAG, "getEventRoot erro: " + e.getMessage());
            return null;
        }
    }

    private double parseDouble(String value) {
        try {
            value = value.replace("\u00A0", "").trim();
            if (value.contains(",")) {
                value = value.replace(".", "").replace(",", ".");
            }
            return Double.parseDouble(value);
        } catch (Exception e) { return 0; }
    }

    @Override public void onInterrupt() { Log.w(TAG, "Serviço interrompido"); }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "=== GigU Accessibility CONECTADO ===");
    }
}
