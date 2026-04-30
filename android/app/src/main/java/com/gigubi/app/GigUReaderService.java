package com.gigubi.app;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import androidx.core.app.NotificationCompat;
import android.view.accessibility.AccessibilityWindowInfo;
import android.graphics.Bitmap;
import android.util.DisplayMetrics;
import android.content.Intent;
import android.view.Display;
import android.view.WindowManager;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import com.getcapacitor.JSObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GigUReaderService extends AccessibilityService {
    private static final String TAG = "GigUReader";
    private static GigUReaderService instance;

    public static GigUReaderService getInstance() { return instance; }

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
    private String lastCapturedText = "";

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

        // ── 0) Caso especial: Notificações (Heads-up) ──
        if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (pkg.contains("app99") || pkg.contains("ubercab")) {
                Log.d(TAG, "Notificação de corrida detectada (" + pkg + ")! Escaneando TODAS as janelas...");
                List<AccessibilityWindowInfo> windows = getWindows();
                if (windows != null) {
                    for (AccessibilityWindowInfo win : windows) {
                        AccessibilityNodeInfo winRoot = win.getRoot();
                        if (winRoot != null) {
                            processWindowRoot(winRoot, event.getEventType());
                            winRoot.recycle();
                        }
                    }
                }
            }
            return;
        }

        // ── Filtro Estrito: Evento OU Janela Ativa devem ser Uber/99 ──
        boolean eventIsUber = pkg.contains("ubercab");
        boolean eventIs99   = pkg.contains("app99") || pkg.contains("taxis") || pkg.contains("noventaenove");

        AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
        String activePkg = "";
        if (activeRoot != null) {
            activePkg = activeRoot.getPackageName() != null ? activeRoot.getPackageName().toString() : "";
            activeRoot.recycle();
        }
        
        boolean activeIsUber = activePkg.contains("ubercab");
        boolean activeIs99   = activePkg.contains("app99") || activePkg.contains("taxis") || activePkg.contains("noventaenove");

        if (!eventIsUber && !eventIs99 && !activeIsUber && !activeIs99) return;

        if (eventIsUber || activeIsUber) currentAppIsUber = true;
        if (eventIs99 || activeIs99)     currentAppIsUber = false;

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
        AccessibilityNodeInfo sourceRoot = event.getSource();
        AccessibilityNodeInfo eventRoot = null;
        if (sourceRoot != null) {
            // Sobe na árvore até a raiz real da janela (evita 'fullText vazio' de views filhas)
            eventRoot = sourceRoot;
            while (eventRoot.getParent() != null) {
                AccessibilityNodeInfo parent = eventRoot.getParent();
                if (eventRoot != sourceRoot) eventRoot.recycle();
                eventRoot = parent;
            }
        }

        int eventWindowId = -1;
        if (eventRoot != null) {
            eventWindowId = eventRoot.getWindowId();
            
            // Atraso tático: Alguns apps (99/Uber) inflam a janela mas demoram ms para colocar o texto.
            // Se processarmos instantâneo, pegamos 'vazio'.
            final AccessibilityNodeInfo finalRoot = eventRoot;
            final int finalType = event.getEventType();
            final CharSequence finalPkg = event.getPackageName();
            
            if (finalPkg != null && (finalPkg.toString().contains("app99") || finalPkg.toString().contains("ubercab"))) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        processWindowRoot(finalRoot, finalType);
                        if (finalRoot != sourceRoot) finalRoot.recycle();
                    } catch (Exception e) {
                        Log.e(TAG, "Erro no delay: " + e.getMessage());
                    }
                }, 500);
            } else {
                processWindowRoot(eventRoot, event.getEventType());
                if (eventRoot != sourceRoot) eventRoot.recycle();
            }
            
            sourceRoot.recycle();
        }

        // ── 2) Fallback: janela ativa (quando source é null) ──
        if (eventRoot == null) {
            AccessibilityNodeInfo fallbackRoot = getRootInActiveWindow();
            if (fallbackRoot != null) {
                eventWindowId = fallbackRoot.getWindowId();
                processWindowRoot(fallbackRoot, event.getEventType());
                fallbackRoot.recycle();
            }
        }

        // ── 3) Janelas extras — Todas exceto teclado ──
        // Lembrete: Nós já temos um filtro de texto no processNode para ignorar
        // nossa própria overlay ('Faltam', 'Simulador'), então é seguro ler TYPE_SYSTEM
        // ── 3) Janelas extras — Todas exceto teclado ──
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                    if (window.getId() == eventWindowId) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) {
                        // Log.d(TAG, "Window root null id=" + window.getId());
                        continue;
                    }
                    
                    // Trace de janela (ajuda a descobrir nomes de pacotes ocultos)
                    CharSequence wPkg = root.getPackageName();
                    if (wPkg != null && (eventIsUber || eventIs99)) {
                        Log.d(TAG, "Window Trace: " + wPkg.toString() + " id=" + window.getId());
                    }

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
        String fullText = sb.toString().trim();
        this.lastCapturedText = fullText;
        
        if (fullText.isEmpty()) {
            Log.d(TAG, "processWindowRoot abortado: [vazio] pkg=" + pkg + " class=" + root.getClassName() + " children=" + root.getChildCount());
            
            // NOVIDADE: Se for 99 ou Uber e estiver vazio, tenta OCR imediatamente!
            String pkgStr = pkg != null ? pkg.toString() : "";
            if (pkgStr.contains("app99") || pkgStr.contains("ubercab")) {
                Log.d(TAG, "Janela vazia detectada no app de transporte. Disparando OCR...");
                triggerOcr();
            }
            return;
        }

        // Diagnóstico especial: Se for SystemUI ou 99/Uber, vamos logar TUDO se tiver qualquer pista
        String low = fullText.toLowerCase();
        if (pkg != null && (pkg.toString().contains("systemui") || pkg.toString().contains("app99") || pkg.toString().contains("ubercab"))) {
            if (low.contains("99") || low.contains("uber") || low.contains("r$") || low.contains("km") || low.contains("aceitar")) {
                Log.d(TAG, "INSPEÇÃO [" + pkg + "]: " + fullText);
            }
        }

        // ── Guard de Conteúdo ──
        // Só processa se parecer um cartão de corrida real (R$ E Palavra de contexto)
        String lowerFull = fullText.toLowerCase();
        boolean hasPrice = lowerFull.contains("r$");
        boolean hasRideKeyword = lowerFull.contains("aceitar") 
            || lowerFull.contains("pagamento")
            || lowerFull.contains("dinheiro")
            || lowerFull.contains("km")
            || lowerFull.contains("min");

        if (!hasPrice || !hasRideKeyword) {
            // Se for do app99 ou uber, vamos logar o texto completo para diagnosticar pq falhou
            if (pkg != null && (pkg.toString().contains("app99") || pkg.toString().contains("ubercab"))) {
                Log.d(TAG, "[SKIP] 99/Uber Relevância falhou. hasPrice=" + hasPrice + " | TEXTO: " + fullText);
            } else if (hasPrice || hasRideKeyword) {
                // Se for outro app mas tiver alguma keyword, vamos logar pra ver
                Log.d(TAG, "[SKIP] Quase passou. hasPrice=" + hasPrice + " | Pkg: " + pkg + " | TEXTO: " + fullText);
            }
            return;
        }

        Log.d(TAG, "FULL_TEXT: " + fullText);

        // Só descarta se for claramente a nossa própria janela de overlay
        String pkgStr = pkg != null ? pkg.toString() : "";
        if (pkgStr.contains("com.gigubi.app")) {
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

        CharSequence className = node.getClassName();
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        
        // Diagnóstico profundo para 99/Uber
        CharSequence nodePkg = node.getPackageName();
        if (nodePkg != null && (nodePkg.toString().contains("app99") || nodePkg.toString().contains("ubercab"))) {
            Log.d(TAG, "[TREE] " + className + " | Text: " + text + " | Desc: " + desc + " | Children: " + node.getChildCount());
        }

        if (text != null && text.length() > 0) {
            sb.append(text.toString()).append(" ");
        }
        if (desc != null && desc.length() > 0) {
            sb.append(desc.toString()).append(" ");
        }
        
        // Campos extras para layouts modernos (Android 8+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            CharSequence hint = node.getHintText();
            if (hint != null && hint.length() > 0) sb.append(hint.toString()).append(" ");
            CharSequence tool = node.getTooltipText();
            if (tool != null && tool.length() > 0) sb.append(tool.toString()).append(" ");
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                extractAllText(child, sb);
                child.recycle();
            }
        }
    }

    private RideInfo extractRideInfo(AccessibilityNodeInfo root, String fullText) {
        // Se o fullText veio vazio mas sabemos que é um app de transporte, 
        // tentamos uma busca direta por "R$" via sistema
        if (fullText.isEmpty()) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("R$");
            if (nodes != null && !nodes.isEmpty()) {
                StringBuilder recoverySb = new StringBuilder();
                for (AccessibilityNodeInfo n : nodes) {
                    if (n.getText() != null) recoverySb.append(n.getText()).append(" ");
                    n.recycle();
                }
                fullText = recoverySb.toString();
                Log.d(TAG, "Recuperação de texto via busca direta: " + fullText);
            }
        }

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
        
        // Ignorar preço por km (ex: R$2,23/km) para não confundir com o preço total
        String cleanText = fullText.replaceAll("R\\\\$[\\\\s\u00A0]*\\\\d+[.,]\\\\d+\\\\s*/\\\\s*km", "");
        
        Matcher pm = PRICE_PATTERN.matcher(cleanText);
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

    public void triggerOcr() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            Log.w(TAG, "OCR não suportado (Requer Android 11+)");
            return;
        }

        // Evita disparar OCR na tela de "Várias solicitações recusadas"
        if (lastCapturedText.contains("solicita") && lastCapturedText.contains("recusadas")) {
            Log.d(TAG, "Tela de aviso detectada. Ignorando OCR.");
            return;
        }

        Log.d(TAG, "Iniciando captura de tela para OCR...");
        
        try {
            // Esconde o overlay nativamente
            GigUPlugin.hideOverlayNative();
            
            // Delay de 150ms para garantir que o overlay sumiu da tela antes do print
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                Executor executor = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P 
                    ? getMainExecutor() 
                    : r -> new android.os.Handler(android.os.Looper.getMainLooper()).post(r);

                takeScreenshot(Display.DEFAULT_DISPLAY, executor, new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult screenshotResult) {
                        Bitmap bitmap = Bitmap.wrapHardwareBuffer(
                            screenshotResult.getHardwareBuffer(),
                            screenshotResult.getColorSpace()
                        );
                        
                        // Volta o overlay imediatamente
                        GigUPlugin.showOverlayNative();
                        
                        if (bitmap != null) {
                            processBitmapWithOcr(bitmap);
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        Log.e(TAG, "Falha ao capturar screenshot: " + errorCode);
                        GigUPlugin.showOverlayNative();
                    }
                });
            }, 150);

        } catch (Exception e) {
            Log.e(TAG, "ERRO CRÍTICO ao tentar screenshot: " + e.getMessage());
            GigUPlugin.showOverlayNative();
        }
    }

    private void processBitmapWithOcr(Bitmap bitmap) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        com.google.mlkit.vision.text.TextRecognizer recognizer = 
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        recognizer.process(image)
            .addOnSuccessListener(visionText -> {
                String resultText = visionText.getText();
                Log.d(TAG, "OCR RESULT: " + resultText);
                GigUPlugin plugin = GigUPlugin.getInstance();
                if (plugin != null) {
                    plugin.processRawText(resultText, "unknown", "OCR");
                }
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Erro no OCR: " + e.getMessage());
            });
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

    private static final String CHANNEL_ID = "gigu_service_channel";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "=== GigU Accessibility CONECTADO ===");
        
        createNotificationChannel();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1001, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1001, createNotification());
        }
    }

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "Ubi Driver Monitor",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ubi Driver Ativo")
            .setContentText("Monitorando ofertas da 99 e Uber...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }
}
