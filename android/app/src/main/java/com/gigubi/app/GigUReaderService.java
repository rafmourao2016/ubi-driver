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
    private double accumTimeMin = 0;
    private double accumSurge = 1.0;
    private long firstEventTime = 0;
    private long lastEmitTime   = 0;
    private String lastEmittedHash = "";
    private Timer idleTimer = null;
    private boolean currentAppIsUber = false;

    private final List<Double> eventKmList = new ArrayList<>();
    private final List<Double> eventTimeList = new ArrayList<>();

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
            notifyDiag("[APP] " + pkg + " | tipo: " + event.getEventType());
            Log.d(TAG, "[PKG_ALL] " + pkg + " | tipo: " + event.getEventType());
        }

        // ── Filtra Uber, 99 e SystemUI (onde overlays podem aparecer) ──
        boolean isUber = pkg.contains("uber");
        boolean is99   = pkg.contains("99")
                      || pkg.contains("taxis")
                      || pkg.contains("noventaenove")
                      || pkg.contains("app99");
        boolean isSystemUI = pkg.contains("systemui");

        if (!isUber && !is99 && !isSystemUI) return;

        if (isUber) currentAppIsUber = true;
        if (is99)   currentAppIsUber = false;
        // se for systemUI, mantém a flag de qual app estava aberto antes

        Log.d(TAG, "Evento: " + pkg + " | tipo: " + event.getEventType());
        notifyDiag("[✓ UBER/99] " + pkg + " tipo:" + event.getEventType());

        long now = System.currentTimeMillis();
        if (now - firstEventTime > ACCUMULATION_WINDOW_MS) {
            accumPrice = 0;
            accumKm = 0;
            accumTimeMin = 0;
            accumSurge = 1.0;
            firstEventTime = now;
        }

        eventKmList.clear();
        eventTimeList.clear();

        // ── 1) Janela do evento (via source node → raiz) ──
        AccessibilityNodeInfo eventRoot = getEventRoot(event);
        int eventWindowId = -1;
        if (eventRoot != null) {
            eventWindowId = eventRoot.getWindowId();
            processWindowRoot(eventRoot, event.getEventType());
            eventRoot.recycle();
        }

        // ── 2) Fallback: janela ativa (quando source é null) ──
        if (eventRoot == null) {
            AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
            if (activeRoot != null) {
                eventWindowId = activeRoot.getWindowId();
                processWindowRoot(activeRoot, event.getEventType());
                activeRoot.recycle();
            }
        }

        // ── 3) Janelas extras — Todas exceto teclado ──
        // Lembrete: Nós já temos um filtro de texto no processNode para ignorar
        // nossa própria overlay ('Faltam', 'Simulador'), então é seguro ler TYPE_SYSTEM
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                    if (window.getId() == eventWindowId) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;
                    processWindowRoot(root, event.getEventType());
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
            double totalKm = 0;
            for (double k : eventKmList) totalKm += k;
            if (totalKm > accumKm) {
                Log.d(TAG, "  -> Km total: " + totalKm + " (" + eventKmList.size() + " trechos)");
                accumKm = totalKm;
            }
            
            double totalTime = 0;
            for (double t : eventTimeList) totalTime += t;
            if (totalTime > accumTimeMin) {
                accumTimeMin = totalTime;
            }
        } else if (!eventKmList.isEmpty()) {
            Log.d(TAG, "  [AGUARDANDO] " + eventKmList.size() + " km — esperando mais trechos");
            notifyDiag("[WAIT] Trechos: " + eventKmList.size() + "/" + minKm + " (p=" + accumPrice + ")");
        }

        Log.d(TAG, "Acumulado: R$" + accumPrice + " | " + accumKm + " km");

        if (accumPrice > 0 && accumKm > 0 && (now - lastEmitTime) > EMIT_THROTTLE_MS) {
            String currentHash = accumPrice + "_" + accumKm + "_" + accumTimeMin + "_" + accumSurge;
            if (!currentHash.equals(lastEmittedHash)) {
                emitOffer(accumPrice, accumKm, accumTimeMin, accumSurge);
                lastEmittedHash = currentHash;
                resetIdleTimer();
            } else {
                Log.d(TAG, "  [DEDUPLICADO] Oferta já emitida: " + currentHash);
            }
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
                lastEmittedHash = ""; // Reseta o hash para permitir novas ofertas iguais depois de um tempo
            }
        }, IDLE_CLEAR_MS);
    }

    public static class RideInfo {
        public double price = 0.0;
        public List<Double> distances = new ArrayList<>();
        public List<Double> times = new ArrayList<>();
        public double surgeMultiplier = 1.0;
        public String layerUsed = "";
        public boolean hasPrice = false;
    }

    private void processWindowRoot(AccessibilityNodeInfo root, int eventType) {
        if (root == null) return;
        
        CharSequence pkg = root.getPackageName();
        if (pkg != null) {
            Log.d(TAG, "processWindowRoot INICIO - pacote: " + pkg.toString() + " | tipo: " + eventType);
        }

        StringBuilder sb = new StringBuilder();
        extractAllText(root, sb);
        String fullText = sb.toString();
        
        if (fullText.trim().isEmpty()) {
            if (pkg != null) {
                Log.d(TAG, "processWindowRoot abortado: fullText vazio (tipo " + eventType + ")");
            }
            return;
        }

        // ── Guard de Relevância ──
        // Só processa se tiver indícios de uma oferta (Preço ou KM)
        if (!fullText.contains("R$") && !fullText.contains("km") && !fullText.contains("min") && !fullText.contains("Aceitar")) {
            // Log.d(TAG, "Guard relevância: texto sem corrida, abortando");
            return;
        }

        Log.d(TAG, "FULL_TEXT: " + fullText);

        String lower = fullText.toLowerCase();
        // Se a janela contiver textos da NOSSA overlay, ignoramos a janela INTEIRA
        if (lower.contains("faltam") || lower.contains("simulador") || lower.contains("simular oferta")) {
            return;
        }

        if (pkg != null) {
            Log.d(TAG, "extractRideInfo chamado - pacote: " + pkg.toString() + " | tipo: " + eventType);
            notifyDiag("[INFO] Extracting: " + pkg.toString());
        }

        RideInfo info = extractRideInfo(root, fullText);
        if (info != null) {
            if (info.hasPrice && info.price > accumPrice) {
                accumPrice = info.price;
                Log.d(TAG, "  -> Preço: R$" + info.price + " [" + info.layerUsed + "]");
            }
            for (Double d : info.distances) {
                if (d > 0.2 && !eventKmList.contains(d)) {
                    eventKmList.add(d);
                    Log.d(TAG, "  -> Km parcial: " + d + " [" + info.layerUsed + "]");
                }
            }
            for (Double t : info.times) {
                if (t > 0 && !eventTimeList.contains(t)) {
                    eventTimeList.add(t);
                }
            }
            if (info.surgeMultiplier > 1.0 && info.surgeMultiplier > accumSurge) {
                accumSurge = info.surgeMultiplier;
            }
        }
    }

    private void extractAllText(AccessibilityNodeInfo node, StringBuilder sb) {
        if (node == null) return;

        CharSequence pkg = node.getPackageName();
        if (pkg != null && pkg.toString().contains("com.gigubi.app")) {
            return;
        }

        CharSequence txt = node.getText();
        CharSequence dsc = node.getContentDescription();
        
        if (txt != null) {
            sb.append(txt.toString().replaceAll("[\t\n\r]", " ")).append(" ");
        }
        if (dsc != null) {
            sb.append(dsc.toString().replaceAll("[\t\n\r]", " ")).append(" ");
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            extractAllText(child, sb);
            if (child != null) child.recycle();
        }
    }

    private RideInfo extractRideInfo(AccessibilityNodeInfo root, String fullText) {
        RideInfo info = new RideInfo();

        // Camada 1: IDs Fixos (mais rápido, mais robusto, mas quebra se app atualizar id)
        if (extractByViewId(root, info)) {
            info.layerUsed = "Camada 1: ViewId";
            return info;
        }
        Log.d(TAG, "Camada 1 falhou");

        // Camada 2: Nós Âncora (busca por texto e vai nos "irmãos/filhos")
        if (extractByAnchor(root, info)) {
            info.layerUsed = "Camada 2: Anchor";
            return info;
        }
        Log.d(TAG, "Camada 2 falhou");

        // Camada 3: Regex na String Gigante (como funcionava antes)
        if (extractByRegex(fullText, info)) {
            info.layerUsed = "Camada 3: Regex";
            return info;
        }
        
        String snippet = fullText.length() > 60 ? fullText.substring(0, 60) + "..." : fullText;
        Log.d(TAG, "Camada 3 falhou — texto concatenado: " + fullText);
        notifyDiag("[FAIL] Layers 1,2,3 failed. Text: " + snippet);

        return null;
    }

    private boolean extractByViewId(AccessibilityNodeInfo root, RideInfo info) {
        boolean found = false;

        // Exemplo: Uber Price
        List<AccessibilityNodeInfo> priceNodes = root.findAccessibilityNodeInfosByViewId("com.ubercab.driver:id/fare_amount");
        if (priceNodes != null) {
            if (!priceNodes.isEmpty()) {
                AccessibilityNodeInfo node = priceNodes.get(0);
                if (node != null && node.getText() != null) {
                    Matcher m = PRICE_PATTERN.matcher(node.getText().toString());
                    if (m.find()) {
                        info.price = parseDouble(m.group(1));
                        info.hasPrice = true;
                        found = true;
                    }
                }
            }
            // Evita vazamento de memória reciclando todos os nós retornados
            for (AccessibilityNodeInfo n : priceNodes) {
                if (n != null) n.recycle();
            }
        }

        // TODO: Mapear IDs para distance, time, surge (Uber/99)
        // Se encontrarmos, preenchemos info e retornamos true.
        // Se não encontrarmos pelo menos o preço, retornamos false para cair na Camada 2.

        return found && info.hasPrice;
    }

    private boolean extractByAnchor(AccessibilityNodeInfo root, RideInfo info) {
        boolean found = false;

        // Âncoras comuns para Uber e 99
        String[] possibleAnchors = {"Pagamento no app", "Dinheiro", "Cartão", "Voucher", "Pix"};

        for (String anchorText : possibleAnchors) {
            List<AccessibilityNodeInfo> anchorNodes = root.findAccessibilityNodeInfosByText(anchorText);
            if (anchorNodes != null) {
                for (AccessibilityNodeInfo anchor : anchorNodes) {
                    if (anchor != null) {
                        // Sobe até 3 níveis na árvore para encontrar a raiz do componente
                        AccessibilityNodeInfo parent = anchor.getParent();
                        int maxDepth = 3;
                        while (parent != null && maxDepth > 0) {
                            if (searchRecursivelyForPrice(parent, info, 3)) { // desce 3 níveis buscando
                                found = true;
                                break;
                            }
                            AccessibilityNodeInfo oldParent = parent;
                            parent = parent.getParent();
                            oldParent.recycle();
                            maxDepth--;
                        }
                        if (parent != null) parent.recycle();
                        anchor.recycle();
                    }
                }
            }
            if (found) break; // Se já encontrou preço usando uma âncora, para de procurar
        }

        // Retorna true somente se encontrou dados vitais usando a âncora
        return found && info.hasPrice;
    }

    private boolean searchRecursivelyForPrice(AccessibilityNodeInfo node, RideInfo info, int depth) {
        if (node == null || depth == 0) return false;
        
        CharSequence txt = node.getText();
        if (txt != null) {
            Matcher pm = PRICE_PATTERN.matcher(txt.toString());
            if (pm.find()) {
                info.price = parseDouble(pm.group(1));
                info.hasPrice = true;
                return true;
            }
        }
        
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            boolean found = searchRecursivelyForPrice(child, info, depth - 1);
            if (child != null) child.recycle();
            if (found) return true;
        }
        return false;
    }

    private boolean extractByRegex(String fullText, RideInfo info) {
        boolean found = false;
        
        Matcher pm = PRICE_PATTERN.matcher(fullText);
        while (pm.find()) {
            double p = parseDouble(pm.group(1));
            if (p > 1 && p < 500) {
                if (p > info.price) info.price = p;
                info.hasPrice = true;
                found = true;
            }
        }
        
        Matcher dm = DISTANCE_PATTERN.matcher(fullText);
        while (dm.find()) {
            double d = parseDouble(dm.group(1));
            String unit = dm.group(2).toLowerCase().trim();
            double km = unit.equals("m") ? d / 1000.0 : d;
            if (km > 0.2 && km < 100) {
                info.distances.add(km);
                found = true;
            }
        }
        
        // Exemplo: 9min
        Matcher tm = Pattern.compile("(\\d+)\\s*(?:min)").matcher(fullText);
        while (tm.find()) {
            double t = parseDouble(tm.group(1));
            if (t > 0 && t < 200) {
                info.times.add(t);
                found = true;
            }
        }
        
        // Exemplo: 1,1x
        Matcher sm = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*[xX]").matcher(fullText);
        while (sm.find()) {
            double s = parseDouble(sm.group(1));
            if (s > 1.0 && s <= 5.0) {
                info.surgeMultiplier = s;
                found = true;
            }
        }

        return found;
    }

    private void emitOffer(double price, double km, double timeMin, double surge) {
        Log.i(TAG, ">>> EMITINDO: R$" + price + " | " + km + " km | " + timeMin + " min | Surge " + surge + "x");
        GigUPlugin plugin = GigUPlugin.getInstance();
        if (plugin != null) {
            plugin.emitOfferReceived("Offer", price, km, timeMin, surge);
            lastEmitTime = System.currentTimeMillis();
            accumPrice = 0; accumKm = 0; accumTimeMin = 0; accumSurge = 1.0; firstEventTime = 0;
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
