package com.gigubi.app;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "GigUPlugin")
public class GigUPlugin extends Plugin {
    private static GigUPlugin instance;

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

    @PluginMethod
    public void updateSettings(PluginCall call) {
        this.fuelPrice = call.getDouble("fuelPrice", 5.80);
        this.fuelConsumption = call.getDouble("fuelConsumption", 10.0);
        this.platformFeePercent = call.getDouble("platformFee", 25.0) / 100.0;
        
        Log.d("GigUPlugin", "Configurações sincronizadas: Fuel=" + fuelPrice + ", Consumo=" + fuelConsumption);
        call.resolve();
    }

    public void emitOfferReceived(String rawText, double price, double km) {
        // Emite para a camada JS (Pode ser usado para logs ou persistência)
        JSObject ret = new JSObject();
        ret.put("rawText", rawText);
        ret.put("price", price);
        ret.put("km", km);
        notifyListeners("onUberOffer", ret);

        // Calcula lucro no lado nativo usando os custos sincronizados
        double fuelCost     = (km / fuelConsumption) * fuelPrice;
        double platformFee  = price * platformFeePercent;
        double netProfit    = price - fuelCost - platformFee;
        double margin       = price > 0 ? (netProfit / price) * 100.0 : 0;
        double profitPerKm  = km > 0 ? netProfit / km : 0;

        // Atualiza a overlay nativa imediatamente
        OverlayPlugin overlay = OverlayPlugin.getInstance();
        if (overlay != null) {
            overlay.updateFromService(netProfit, margin, profitPerKm);
        }

        // Dispara feedback (vibração + som)
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
}


