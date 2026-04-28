package com.gigubi.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    private GradientDrawable badgeBg;
    private WindowManager.LayoutParams params;

    // Debounce: evita piscar ao receber eventos repetidos com valores iguais
    private double lastRenderedProfit = Double.MIN_VALUE;
    private double lastRenderedMargin = Double.MIN_VALUE;

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
        double margin    = call.getDouble("margin", 0.0);
        double profitPerKm = call.getDouble("profitPerKm", 0.0);

        getActivity().runOnUiThread(() -> {
            if (!Settings.canDrawOverlays(getContext())) {
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
        double netProfit   = call.getDouble("netProfit", 0.0);
        double margin      = call.getDouble("margin", 0.0);
        double profitPerKm = call.getDouble("profitPerKm", 0.0);
        getActivity().runOnUiThread(() -> { updateView(netProfit, margin, profitPerKm); call.resolve(); });
    }

    @PluginMethod
    public void hideOverlay(PluginCall call) {
        getActivity().runOnUiThread(() -> { removeOverlay(); call.resolve(); });
    }

    @PluginMethod
    public void isOverlayVisible(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("visible", overlayView != null);
        call.resolve(ret);
    }

    public void updateFromService(double netProfit, double margin, double profitPerKm) {
        if (Math.abs(netProfit - lastRenderedProfit) < 0.05
                && Math.abs(margin - lastRenderedMargin) < 0.5) return;
        lastRenderedProfit = netProfit;
        lastRenderedMargin = margin;
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (overlayView != null) updateView(netProfit, margin, profitPerKm);
        });
    }

    public void clearOverlay() {
        lastRenderedProfit = Double.MIN_VALUE;
        lastRenderedMargin = Double.MIN_VALUE;
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (profitText == null) return;
            profitText.setText("Aguardando...");
            statsText.setText("Nenhuma oferta");
            badgeText.setText("OCIOSO");
            badgeBg.setColor(Color.parseColor("#374151"));
        });
    }

    // ─────────────────────────────────────────────────────────────
    //  Design "fofinho" — cantos arredondados, gradiente, pill badge
    // ─────────────────────────────────────────────────────────────
    private void createOverlay(double netProfit, double margin, double profitPerKm) {
        windowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);

        // ── Root frame com cantos arredondados e gradiente ──
        overlayView = new FrameLayout(getContext());

        GradientDrawable rootBg = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[]{ Color.parseColor("#F0131325"), Color.parseColor("#F00D0D1C") }
        );
        rootBg.setCornerRadius(dp(22));
        rootBg.setStroke(dp(1), Color.parseColor("#6D28D9")); // borda roxa
        overlayView.setBackground(rootBg);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            overlayView.setElevation(dp(8));
        }

        // ── Inner layout ──
        LinearLayout inner = new LinearLayout(getContext());
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(14), dp(10), dp(14), dp(12));

        // ── Linha superior: título | espaço | badge | fechar ──
        LinearLayout topRow = new LinearLayout(getContext());
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(getContext());
        title.setText("UBI");
        title.setTextColor(Color.parseColor("#A78BFA"));
        title.setTextSize(9);
        title.setTypeface(null, Typeface.BOLD);
        title.setLetterSpacing(0.15f);

        View spacer = new View(getContext());
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));

        // Badge pill arredondado
        badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(20));

        badgeText = new TextView(getContext());
        badgeText.setTextSize(9);
        badgeText.setTypeface(null, Typeface.BOLD);
        badgeText.setTextColor(Color.WHITE);
        badgeText.setPadding(dp(8), dp(3), dp(8), dp(3));
        badgeText.setBackground(badgeBg);

        // Botão fechar
        TextView closeBtn = new TextView(getContext());
        closeBtn.setText("  ✕");
        closeBtn.setTextColor(Color.parseColor("#6B7280"));
        closeBtn.setTextSize(13);
        closeBtn.setOnClickListener(v -> removeOverlay());

        topRow.addView(title);
        topRow.addView(spacer);
        topRow.addView(badgeText);
        topRow.addView(closeBtn);

        // ── Lucro principal ──
        profitText = new TextView(getContext());
        profitText.setTextColor(Color.WHITE);
        profitText.setTextSize(30);
        profitText.setTypeface(null, Typeface.BOLD);
        profitText.setPadding(0, dp(6), 0, dp(2));

        // ── Stats: margem e por km ──
        statsText = new TextView(getContext());
        statsText.setTextColor(Color.parseColor("#9CA3AF"));
        statsText.setTextSize(11);

        inner.addView(topRow);
        inner.addView(profitText);
        inner.addView(statsText);
        overlayView.addView(inner);

        updateView(netProfit, margin, profitPerKm);

        // ── WindowManager params ──
        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                dp(210),
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(10);
        params.y = dp(80);

        // ── Drag ──
        overlayView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX     = params.x;
                        initialY     = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX - (int)(event.getRawX() - initialTouchX);
                        params.y = initialY + (int)(event.getRawY() - initialTouchY);
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

        profitText.setText(String.format("R$ %.2f", netProfit).replace('.', ','));
        statsText.setText(
            String.format("%.1f%% margem  ·  R$%.2f/km", margin, profitPerKm).replace('.', ',')
        );

        if (margin >= 30) {
            badgeText.setText("ELITE ★");
            badgeBg.setColor(Color.parseColor("#059669")); // verde esmeralda
        } else if (margin >= 15) {
            badgeText.setText("OK ●");
            badgeBg.setColor(Color.parseColor("#D97706")); // âmbar
        } else {
            badgeText.setText("BAIXO ▼");
            badgeBg.setColor(Color.parseColor("#DC2626")); // vermelho
        }
    }

    private void removeOverlay() {
        if (overlayView != null && windowManager != null) {
            try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
            overlayView = null;
            profitText  = null;
            statsText   = null;
            badgeText   = null;
            badgeBg     = null;
        }
    }

    private int dp(int value) {
        return (int)(value * getContext().getResources().getDisplayMetrics().density);
    }
}
