package com.gigubi.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "OverlayPlugin")
public class OverlayPlugin extends Plugin {

    private static OverlayPlugin instance;
    private WindowManager windowManager;
    private FrameLayout overlayView;
    private TextView profitText;
    private TextView statsText;
    private TextView badgeText;
    private WindowManager.LayoutParams params;

    @Override
    public void load() {
        super.load();
        instance = this;
    }

    public static OverlayPlugin getInstance() {
        return instance;
    }

    @PluginMethod
    public void showOverlay(PluginCall call) {
        double netProfit = call.getDouble("netProfit", 0.0);
        double margin = call.getDouble("margin", 0.0);
        double profitPerKm = call.getDouble("profitPerKm", 0.0);

        getActivity().runOnUiThread(() -> {
            if (!Settings.canDrawOverlays(getContext())) {
                // Pede permissão ao usuário
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getContext().getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
                call.reject("Permissão de overlay necessária");
                return;
            }

            if (overlayView != null) {
                updateView(netProfit, margin, profitPerKm);
                call.resolve();
                return;
            }

            createOverlay(netProfit, margin, profitPerKm);
            call.resolve();
        });
    }

    @PluginMethod
    public void updateOverlay(PluginCall call) {
        double netProfit = call.getDouble("netProfit", 0.0);
        double margin = call.getDouble("margin", 0.0);
        double profitPerKm = call.getDouble("profitPerKm", 0.0);

        getActivity().runOnUiThread(() -> {
            updateView(netProfit, margin, profitPerKm);
            call.resolve();
        });
    }

    @PluginMethod
    public void hideOverlay(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            removeOverlay();
            call.resolve();
        });
    }

    @PluginMethod
    public void isOverlayVisible(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("visible", overlayView != null);
        call.resolve(ret);
    }

    // Chamado internamente pelo GigUPlugin sem necessidade de PluginCall
    public void updateFromService(double netProfit, double margin, double profitPerKm) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (overlayView != null) {
                updateView(netProfit, margin, profitPerKm);
            }
        });
    }

    private void createOverlay(double netProfit, double margin, double profitPerKm) {
        windowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);

        // Root container com fundo escuro translúcido
        overlayView = new FrameLayout(getContext());
        overlayView.setBackgroundColor(Color.parseColor("#E6121212"));

        // Inner layout vertical
        LinearLayout inner = new LinearLayout(getContext());
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(16), dp(12), dp(16), dp(12));

        // ── Badge (ELITE / ACEITÁVEL / BAIXO LUCRO) ──
        badgeText = new TextView(getContext());
        badgeText.setTextSize(11);
        badgeText.setAllCaps(true);
        badgeText.setPadding(dp(10), dp(4), dp(10), dp(4));
        badgeText.setTextColor(Color.WHITE);

        LinearLayout badgeRow = new LinearLayout(getContext());
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setGravity(Gravity.END);

        // ── Botão fechar ──
        TextView closeBtn = new TextView(getContext());
        closeBtn.setText("  ✕");
        closeBtn.setTextColor(Color.parseColor("#888888"));
        closeBtn.setTextSize(16);
        closeBtn.setOnClickListener(v -> removeOverlay());

        badgeRow.addView(badgeText);
        badgeRow.addView(closeBtn);

        // ── Titulo ──
        TextView title = new TextView(getContext());
        title.setText("UBI SMART DRIVER");
        title.setTextColor(Color.parseColor("#A78BFA"));
        title.setTextSize(10);
        title.setAllCaps(true);

        // ── Lucro principal ──
        profitText = new TextView(getContext());
        profitText.setTextColor(Color.WHITE);
        profitText.setTextSize(32);
        profitText.setPadding(0, dp(2), 0, dp(2));

        // ── Stats secundários ──
        statsText = new TextView(getContext());
        statsText.setTextColor(Color.parseColor("#AAAAAA"));
        statsText.setTextSize(13);

        inner.addView(badgeRow);
        inner.addView(title);
        inner.addView(profitText);
        inner.addView(statsText);
        overlayView.addView(inner);

        // Atualiza valores iniciais
        updateView(netProfit, margin, profitPerKm);

        // WindowManager params
        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                dp(260),
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(8);
        params.y = dp(80);

        // Drag support
        overlayView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX - (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(overlayView, params);
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(overlayView, params);
    }

    private void updateView(double netProfit, double margin, double profitPerKm) {
        if (profitText == null) return;

        // Formata valores
        String profit = String.format("R$ %.2f", netProfit).replace('.', ',');
        String stats = String.format("Margem: %.1f%%  •  R$%.2f/km", margin, profitPerKm)
                .replace('.', ',');

        profitText.setText(profit);
        statsText.setText(stats);

        // Badge colorido por margem
        if (margin >= 30) {
            badgeText.setText("ELITE");
            badgeText.setBackgroundColor(Color.parseColor("#16A34A"));
        } else if (margin >= 15) {
            badgeText.setText("ACEITÁVEL");
            badgeText.setBackgroundColor(Color.parseColor("#CA8A04"));
        } else {
            badgeText.setText("BAIXO LUCRO");
            badgeText.setBackgroundColor(Color.parseColor("#DC2626"));
        }
    }

    private void removeOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {}
            overlayView = null;
            profitText = null;
            statsText = null;
            badgeText = null;
        }
    }

    private int dp(int value) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return (int) (value * density);
    }
}
