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
    private static final long IDLE_CLEAR_MS    = 60000;
    private static final double MIN_PRICE_THRESHOLD = 5.00; // Piso de R$ 5,00

    private static final Pattern PRICE_PATTERN = Pattern.compile(
        "R\\$[\\s\u00A0]*(\\d+(?:[.,]\\d+)?)"
    );
    private static final Pattern SURGE_PATTERN = Pattern.compile(
        "(?:[^\\d]|^)([1-5][.,]\\d)x", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISTANCE_PATTERN = Pattern.compile(
        "\\(?([\\d]+(?:[.,]\\d+)?)\\s*(km|m)\\b\\)?",
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
    private long lastOcrTime = 0;
    private static final long OCR_THROTTLE_MS = 5000;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (pkg.isEmpty()) return;


        // ── 0) Caso especial: Notificações (Heads-up) ──
        if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (pkg.contains("app99") || pkg.contains("ubercab")) {
                Log.d(TAG, "Notificação detectada! Resetando throttle e aguardando 800ms...");
                lastOcrTime = 0; // RESET: Permite OCR imediato para esta nova oferta
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    triggerOcr();
                }, 800);
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

        // Se temos preço, já podemos emitir (mesmo sem KM, mostramos o que temos)
        if (accumPrice >= MIN_PRICE_THRESHOLD && (now - lastEmitTime) > EMIT_THROTTLE_MS) {
            String currentHash = accumPrice + "_" + accumKm + "_" + accumTimeMin + "_" + accumSurge;
            if (!currentHash.equals(lastEmittedHash)) {
                emitOffer(accumPrice, accumKm, accumTimeMin, accumSurge);
                lastEmittedHash = currentHash;
                resetIdleTimer();
            }
        }
    }

    private void resetIdleTimer() {
        if (idleTimer != null) idleTimer.cancel();
        idleTimer = new Timer();
        idleTimer.schedule(new TimerTask() {
            @Override public void run() {
                Log.d(TAG, "[IDLE] 8s sem oferta");
                    GigUPlugin plugin = GigUPlugin.getInstance();
                    if (plugin != null && accumPrice <= 0) { // SÓ limpa se não houver valor ativo
                        // Em vez de esconder, apenas limpamos para "Aguardando..."
                        OverlayPlugin overlay = OverlayPlugin.getInstance();
                        if (overlay != null) overlay.clearOverlay();
                        accumPrice = 0;
                        accumKm = 0;
                        accumTimeMin = 0;
                        lastEmitTime = 0;
                        lastEmittedHash = "";
                    }
 // Reseta o hash para permitir novas ofertas iguais depois de um tempo
            }
        }, IDLE_CLEAR_MS);
    }

    public void processRawTextOcr(String text) {
        RideInfo info = new RideInfo();
        if (extractByRegex(text, info)) {
            Log.d(TAG, "OCR processado - preço: " + info.price + " | km: " + info.km);
            if (info.hasPrice && info.price > accumPrice) {
                accumPrice = info.price;
                accumKm    = info.km;
                accumTimeMin = info.timeMin;
                accumSurge = info.surge;
                lastEmitTime = 0; // Força emissão imediata
                checkAndEmitIfReady();
            }
        } else {
            Log.d(TAG, "Falha ao extrair dados do texto OCR via Regex.");
        }
    }

    private void checkAndEmitIfReady() {
        long now = System.currentTimeMillis();
        if (accumPrice >= MIN_PRICE_THRESHOLD && (now - lastEmitTime) > EMIT_THROTTLE_MS) {
            String currentHash = accumPrice + "_" + accumKm + "_" + accumTimeMin + "_" + accumSurge;
            if (!currentHash.equals(lastEmittedHash)) {
                emitOffer(accumPrice, accumKm, accumTimeMin, accumSurge);
                lastEmittedHash = currentHash;
                resetIdleTimer();
            }
        }
    }

    public static class RideInfo {
        public double price = 0.0;
        public List<Double> distances = new ArrayList<>();
        public List<Double> times = new ArrayList<>();
        public double surgeMultiplier = 1.0;
        public String layerUsed = "";
        public boolean hasPrice = false;
        public double km = 0.0;
        public double timeMin = 0.0;
        public double surge = 1.0;
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

        String cleanText = fullText.replace("UBI", "")
            .replace("margem", "")
            .replace("Faltam", "")
            .replace("ELITE", "")
            .trim();

        if (cleanText.isEmpty()) {
            Log.d(TAG, "processWindowRoot abortado: [vazio ou apenas overlay] pkg=" + pkg + " children=" + root.getChildCount());
            
            // Tenta OCR se a tela parecer travada/vazia (Uber ou 99)
            String pkgStr = pkg != null ? pkg.toString() : "";
            if (pkgStr.contains("app99") || pkgStr.contains("ubercab") || pkgStr.contains("uber")) {
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

        // ── Guard de Relevância Inteligente ──
        String lowerFull = fullText.toLowerCase();
        
        // 1. Blacklist de Telas Conhecidas (Menu Uber, Offline, etc)
        if (fullText.contains("Página inicial") || fullText.contains("Você está offline") || fullText.contains("Pesquisar locais")) {
            return;
        }

        // 2. Só aceita se tiver Preço REAL (>0) E dados de corrida (km e min)
        boolean temPrecoReal = fullText.matches(".*R\\$[\\s\u00A0]*[1-9]\\d*[.,]\\d+.*");
        boolean temKm = lowerFull.contains("km");
        boolean temMin = lowerFull.contains("min");

        if (!temPrecoReal || !temKm || !temMin) {
            // Se NÃO tem os dados, mas é o mapa, limpamos
            if (lowerFull.contains("onde vamos") || lowerFull.contains("para onde") || lowerFull.contains("procurar")) {
                OverlayPlugin overlay = OverlayPlugin.getInstance();
                if (overlay != null) overlay.clearOverlay();
                accumPrice = 0; accumKm = 0; lastEmitTime = 0;
            }
            return;
        }

        // 1. Blacklist de contexto: ignora preços de gasolina/postos
        String cleanText2 = fullText
            .replaceAll("(?i)99 Abastece.*", "")
            .replaceAll("(?i)Posto.*", "")
            .replaceAll("(?i)Combustív.*", "");

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

        RideInfo info = extractRideInfo(root, cleanText2);
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

    private boolean extractByRegex(String rawText, RideInfo info) {
        boolean found = false;
        
        // 1. Limpeza rigorosa: remove nosso próprio overlay e lixo antes de rodar o regex
        String ocrClean = rawText
            .replaceAll("(?i)[^\\n]*faltam[^\\n]*", "")
            .replaceAll("(?i)[^\\n]*margem[^\\n]*", "")
            .replaceAll("(?i)[^\\n]*UBI[^\\n]*", "")
            .replaceAll("(?i)[^\\n]*afeta a TA[^\\n]*", "")
            .replaceAll("R\\$0,00", "")
            .replaceAll("R\\$[\\s\u00A0]*\\d+[.,]\\d+\\s*/\\s*km", ""); // remove preço/km

        // Normaliza O maiúsculo antes de números (OCR confunde O com 0)
        // Ex: "O3min" -> "03min"
        ocrClean = ocrClean.replaceAll("(?<=[^a-zA-Z])O(?=\\d)", "0");
        
        // 2. Busca o PRIMEIRO preço válido entre R$5 e R$200
        Matcher pm = PRICE_PATTERN.matcher(ocrClean);
        while (pm.find()) {
            double p = parseDouble(pm.group(1));
            if (p >= 5.0 && p <= 200.0 && !info.hasPrice) {
                info.price = p;
                info.hasPrice = true;
                found = true;
                break; // Para no primeiro válido
            }
        }
        
        // 3. Distâncias (SOMA TODOS OS TRECHOS)
        Matcher dm = DISTANCE_PATTERN.matcher(ocrClean);
        double totalKmFound = 0;
        while (dm.find()) {
            double d = parseDouble(dm.group(1));
            String unit = dm.group(2).toLowerCase().trim();
            double km = unit.equals("m") ? d / 1000.0 : d;
            if (km > 0.05 && km < 100) {
                info.distances.add(km);
                totalKmFound += km;
                found = true;
            }
        }
        info.km = totalKmFound;
        
        // 4. Tempo (Ex: 03min) (SOMA TODOS OS TRECHOS)
        Matcher tm = Pattern.compile("(\\d+)\\s*(?:min)").matcher(ocrClean);
        double totalTimeFound = 0;
        while (tm.find()) {
            double t = parseDouble(tm.group(1));
            if (t > 0 && t < 200) {
                info.times.add(t);
                totalTimeFound += t;
                found = true;
            }
        }
        info.timeMin = totalTimeFound;
        
        // Exemplo: 1,1x ou *1,1x (Trava estrita: 1.1 a 4.9)
        Matcher sm = SURGE_PATTERN.matcher(ocrClean);
        while (sm.find()) {
            double s = parseDouble(sm.group(1));
            
            // Correção automática de vírgula perdida: 13x -> 1.3x
            if (s >= 10 && s < 50) {
                double oldS = s;
                s = s / 10.0;
                Log.d(TAG, "[SURGE_FIX] Corrigido de " + oldS + " para " + s);
            }
            
            if (s >= 1.1 && s <= 4.9) {
                info.surgeMultiplier = s;
                info.surge = s;
                found = true;
            }
        }
        
        return found;
    }

    private void emitOffer(double price, double km, double timeMin, double surge) {
        // Arredonda km para 1 casa decimal para o overlay (ex: 3.48 -> 3.5)
        double roundedKm = Math.round(km * 10.0) / 10.0;
        
        Log.i(TAG, ">>> EMITINDO: R$" + price + " | " + roundedKm + " km | " + timeMin + " min | Surge " + surge + "x");
        GigUPlugin plugin = GigUPlugin.getInstance();
        if (plugin != null) {
            plugin.showOverlayNative(); // Garante que o overlay apareça quando há corrida
            plugin.emitOfferReceived("Offer", price, roundedKm, timeMin, surge);
            lastEmitTime = System.currentTimeMillis();
            accumPrice = 0; accumKm = 0; accumTimeMin = 0; accumSurge = 1.0; firstEventTime = 0;
        } else {
            Log.e(TAG, "GigUPlugin null!");
            notifyDiag("[LEITOR] GigUPlugin null — WebView em background?");
        }
    }

    /** Emite log de diagnóstico para o UI do app */
    private void notifyDiag(String msg) {
        // Silenciado para performance
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

        long now = System.currentTimeMillis();
        if (now - lastOcrTime < OCR_THROTTLE_MS) {
            Log.d(TAG, "OCR ignorado (throttled)");
            return;
        }
        lastOcrTime = now;

        // Evita disparar OCR na tela de "Várias solicitações recusadas"
        if (lastCapturedText.contains("solicita") && lastCapturedText.contains("recusadas")) {
            Log.d(TAG, "Tela de aviso detectada. Ignorando OCR.");
            return;
        }

        Log.d(TAG, "Iniciando captura de tela para OCR...");
        
        // --- PISCADA DE SEGURANÇA ---
        // Esconde o overlay antes do print para não sujar o OCR
        OverlayPlugin overlay = OverlayPlugin.getInstance();
        if (overlay != null) {
            overlay.setOverlayVisibility(false);
        }

        // Delay de 100ms para garantir que o Android processou o "hide" na tela
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                Executor executor = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P 
                    ? getMainExecutor() 
                    : r -> new android.os.Handler(android.os.Looper.getMainLooper()).post(r);

                takeScreenshot(Display.DEFAULT_DISPLAY, executor, new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult screenshotResult) {
                        // Volta o overlay imediatamente após o print
                        if (overlay != null) {
                            overlay.setOverlayVisibility(true);
                        }

                    Bitmap bitmap = Bitmap.wrapHardwareBuffer(
                        screenshotResult.getHardwareBuffer(),
                        screenshotResult.getColorSpace()
                    );
                    
                    if (bitmap == null) {
                        Log.e(TAG, "OCR FALHOU: bitmap null");
                        return;
                    }
                    Log.d(TAG, "OCR bitmap: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                    processBitmapWithOcr(bitmap);
                }

                @Override
                public void onFailure(int errorCode) {
                    // Volta o overlay mesmo em falha
                    if (overlay != null) {
                        overlay.setOverlayVisibility(true);
                    }
                    Log.e(TAG, "Falha ao capturar screenshot: " + errorCode);
                }
            });

            } catch (Exception e) {
                // Garante volta do overlay em erro crítico
                if (overlay != null) overlay.setOverlayVisibility(true);
                Log.e(TAG, "ERRO CRÍTICO ao tentar screenshot: " + e.getMessage());
            }
        }, 100);
    }

    private void processBitmapWithOcr(Bitmap bitmap) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        com.google.mlkit.vision.text.TextRecognizer recognizer = 
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        recognizer.process(image)
            .addOnSuccessListener(visionText -> {
                String resultText = visionText.getText();
                Log.d(TAG, "OCR SUCESSO: " + resultText.length() + " chars");
                String lowText = resultText.toLowerCase();
                
                // Validação rigorosa: só aceita se tiver km, min e r$ juntos
                if (!lowText.contains("km") || !lowText.contains("min") || !lowText.contains("r$")) {
                    Log.d(TAG, "OCR descartado - não parece um cartão de corrida completo.");
                    return;
                }

                Log.d(TAG, "OCR RESULT (VALIDADO): " + resultText);
                GigUPlugin plugin = GigUPlugin.getInstance();
                if (plugin != null) {
                    plugin.processRawText(resultText, "unknown", "OCR");
                }
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "OCR FALHOU: " + e.getMessage());
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
