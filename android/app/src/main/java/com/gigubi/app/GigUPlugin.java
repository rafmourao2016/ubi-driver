package com.gigubi.app;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CapacitorPlugin(name = "GigUPlugin")
public class GigUPlugin extends Plugin {
    private static GigUPlugin instance;

    private static final Pattern PRICE_PATTERN = Pattern.compile("R\\$[\\s\u00A0]*(\\d+(?:[.,]\\d+)?)");
    private static final Pattern DISTANCE_PATTERN = Pattern.compile("(\\d+(?:[.,]\\d+)?)[\\s\u00A0]*(km|m)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d+)[\\s\u00A0]*(min|mín)\\b", Pattern.CASE_INSENSITIVE);

    // Custos mutáveis sincronizados pelo JavaScript
    private double fuelPrice = 5.80;
    private double fuelConsumption = 10.0;
    private double platformFeePercent = 0.25; // 25%

    @Override
    public void load() {
        super.load();
        instance = this;
    }

    public static GigUPlugin getInstance() {
        return instance;
    }

    /** Wrapper público para emitir logs de diagnóstico ao JS (notifyListeners é protected) */
    public void sendDiagLog(String msg) {
        try {
            JSObject evt = new JSObject();
            evt.put("msg", msg);
            notifyListeners("onDiagLog", evt);
        } catch (Exception ignored) {}
    }

    @PluginMethod
    public void updateSettings(PluginCall call) {
        this.fuelPrice = call.getDouble("fuelPrice", 5.80);
        this.fuelConsumption = call.getDouble("fuelConsumption", 10.0);
        this.platformFeePercent = call.getDouble("platformFee", 25.0) / 100.0;
        Log.d("GigUPlugin", "Config: Fuel=" + fuelPrice + " Consumo=" + fuelConsumption);
        call.resolve();
    }

    /**
     * Simula uma oferta de corrida para testar TODO o pipeline
     * (overlay, vibração, cálculo) sem precisar receber corrida real.
     * Parâmetros opcionais: price (padrão 18.50), km (padrão 7.2)
     */
    @PluginMethod
    public void simulateOffer(PluginCall call) {
        double price = call.getDouble("price", 18.50);
        double km    = call.getDouble("km", 7.2);
        Log.i("GigUPlugin", "[SIMULAÇÃO] Disparando oferta fake: R$" + price + " | " + km + " km");
        emitOfferReceived("SIMULAÇÃO", price, km, 0.0, 1.0);
        call.resolve();
    }


    /** Verifica se as duas permissões foram concedidas */
    @PluginMethod
    public void checkPermissions(PluginCall call) {
        Log.d("GigUPlugin", "checkPermissions chamado");
        boolean hasOverlay      = Settings.canDrawOverlays(getContext());
        boolean hasAccessibility = isAccessibilityServiceEnabled();
        boolean hasNotification = isNotificationServiceEnabled();
        boolean isRunning       = GigUReaderService.getInstance() != null;

        JSObject ret = new JSObject();
        ret.put("overlayGranted", hasOverlay);
        ret.put("accessibilityGranted", hasAccessibility);
        ret.put("notificationGranted", hasNotification);
        ret.put("serviceRunning", isRunning);
        call.resolve(ret);
    }

    private boolean isNotificationServiceEnabled() {
        String pkgName = getContext().getPackageName();
        final String flat = Settings.Secure.getString(getContext().getContentResolver(),
                "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                if (name.contains(pkgName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Abre direto a tela de permissão 'Aparecer sobre outros apps' do Ubi */
    @PluginMethod
    public void openOverlayPermission(PluginCall call) {
        Intent intent = new Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getContext().getPackageName())
        );
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }

    /** Abre direto as configurações de Acessibilidade do Android */
    @PluginMethod
    public void openAccessibilitySettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }

    /** Abre a tela de 'Acesso a Notificações' */
    @PluginMethod
    public void openNotificationSettings(PluginCall call) {
        Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }

    /** Verifica se o GigUReaderService está ativo na Acessibilidade */
    private boolean isAccessibilityServiceEnabled() {
        try {
            String prefString = Settings.Secure.getString(
                getContext().getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            if (prefString == null || prefString.isEmpty()) return false;
            String ourService = getContext().getPackageName() + "/" +
                GigUReaderService.class.getName();
            for (String entry : prefString.split(":")) {
                if (entry.equalsIgnoreCase(ourService)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public void processRawText(String rawText, String pkg, String source) {
        if (rawText == null || rawText.isEmpty()) return;

        // VACINA: Ignora se o texto parece vir do nosso próprio overlay
        // Isso evita "eco" e cálculos errados baseados no que nós mesmos escrevemos na tela
        if (rawText.contains("UBI") || rawText.contains("margem") || rawText.contains("Faltam")) {
            Log.d("GigUPlugin", "[FILTRO] Texto do overlay ignorado para evitar eco.");
            return;
        }

        double price = 0;
        double km = 0;
        double time = 0;

        Matcher pm = PRICE_PATTERN.matcher(rawText);
        if (pm.find()) price = parseDouble(pm.group(1));

        Matcher dm = DISTANCE_PATTERN.matcher(rawText);
        // Pre-carrega o primeiro km para o sanity check se existir
        double firstKm = 0;
        if (dm.find()) {
            double d = parseDouble(dm.group(1));
            String unit = dm.group(2).toLowerCase();
            firstKm = unit.equals("m") ? d / 1000.0 : d;
            dm.reset(); // Reseta para o loop while não perder o primeiro valor
        }

        // SANITY CHECK: Se o preço for > 200 e o primeiro km for muito baixo/zero, ignoramos
        if (price > 200 && firstKm <= 0.1) {
            Log.d("GigUPlugin", "[SANITY] Preço suspeito ignorado: R$" + price + " com " + firstKm + "km");
            return;
        }

        while (dm.find()) {
            double d = parseDouble(dm.group(1));
            String unit = dm.group(2).toLowerCase();
            if (unit.equals("m")) d = d / 1000.0;
            if (d > km) km = d;
        }

        Matcher tm = TIME_PATTERN.matcher(rawText);
        if (tm.find()) time = parseDouble(tm.group(1));

        Log.d("GigUPlugin", "[" + source + "] Extraído: R$" + price + " | " + km + "km | " + time + "min");

        if (price > 0 && km > 0) {
            emitOfferReceived(rawText, price, km, time, 1.0);
        } else {
            // Se falhou e veio da Notificação, pode sinalizar para tentar OCR
            if (source.equals("Notification")) {
                Log.d("GigUPlugin", "Notificação incompleta. OCR pode ser necessário.");
                // Aqui poderíamos disparar o OCR no ReaderService via um broadcast ou singleton
                GigUReaderService reader = GigUReaderService.getInstance();
                if (reader != null) {
                    reader.triggerOcr();
                }
            }
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

    public void emitOfferReceived(String rawText, double price, double km, double timeMin, double surge) {
        // Emite para a camada JS
        JSObject ret = new JSObject();
        ret.put("rawText", rawText);
        ret.put("price", price);
        ret.put("km", km);
        ret.put("timeMin", timeMin);
        ret.put("surge", surge);
        notifyListeners("onUberOffer", ret);

        // Calcula lucro no lado nativo
        double fuelCost    = (km / fuelConsumption) * fuelPrice;
        double platformFee = price * platformFeePercent;
        double netProfit   = price - fuelCost - platformFee;
        double margin      = price > 0 ? (netProfit / price) * 100.0 : 0;
        double profitPerKm = km > 0 ? netProfit / km : 0;

        // Emite log de diagnóstico para o UI
        JSObject logEvt = new JSObject();
        logEvt.put("msg", String.format("[OFERTA] R$%.2f | %.1fkm | %.1fmin | Surge %.1fx | Lucro: R$%.2f (%.0f%%)",
            price, km, timeMin, surge, netProfit, margin));
        notifyListeners("onDiagLog", logEvt);

        // Atualiza overlay nativo
        OverlayPlugin overlay = OverlayPlugin.getInstance();
        if (overlay != null) {
            overlay.updateFromService(netProfit, margin, profitPerKm);
        } else {
            JSObject logEvt2 = new JSObject();
            logEvt2.put("msg", "[ERRO] OverlayPlugin null — overlay não está ativo");
            notifyListeners("onDiagLog", logEvt2);
        }

        triggerFeedback(margin);
    }



    private void triggerFeedback(double margin) {
        Context ctx = getContext();
        if (ctx == null) return;

        Vibrator vibrator = getVibrator(ctx);
        if (vibrator == null) return;

        if (margin >= 30) {
            // ★ ELITE — dois pulsos fortes + som agudo de sucesso
            vibratePattern(vibrator, new long[]{0, 250, 120, 250}, new int[]{255, 255, 0, 255});
            playTone(ToneGenerator.TONE_PROP_ACK, 400);   // tom duplo positivo

        } else if (margin >= 15) {
            // ● ACEITÁVEL — um pulso médio + som neutro
            vibratePattern(vibrator, new long[]{0, 150}, new int[]{180, 180});
            playTone(ToneGenerator.TONE_PROP_BEEP, 200);

        } else {
            // ▼ BAIXO LUCRO — buzz curto e fraco, sem som
            vibratePattern(vibrator, new long[]{0, 80}, new int[]{100, 100});
        }
    }

    private void vibratePattern(Vibrator vibrator, long[] pattern, int[] amplitudes) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        } catch (Exception e) {
            // Dispositivo sem suporte a amplitudes — fallback simples
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
                } else {
                    vibrator.vibrate(pattern, -1);
                }
            } catch (Exception ignored) {}
        }
    }

    private void playTone(int toneType, int durationMs) {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            tg.startTone(toneType, durationMs);
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    private Vibrator getVibrator(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                return vm != null ? vm.getDefaultVibrator() : null;
            } else {
                return (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static void showOverlayNative() {
        OverlayPlugin op = OverlayPlugin.getInstance();
        if (op != null) op.setOverlayVisibility(true);
    }

    public static void hideOverlayNative() {
        OverlayPlugin op = OverlayPlugin.getInstance();
        if (op != null) op.setOverlayVisibility(false);
    }
}


