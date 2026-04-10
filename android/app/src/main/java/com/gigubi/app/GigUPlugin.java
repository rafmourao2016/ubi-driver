package com.gigubi.app;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "GigUPlugin")
public class GigUPlugin extends Plugin {
    private static GigUPlugin instance;

    // Default costs for native-side calculation
    private static final double FUEL_PRICE = 5.80;
    private static final double FUEL_CONSUMPTION = 10.0; // km/l
    private static final double PLATFORM_FEE = 0.25;    // 25%

    @Override
    public void load() {
        super.load();
        instance = this;
    }

    public static GigUPlugin getInstance() {
        return instance;
    }

    public void emitOfferReceived(String rawText, double price, double km) {
        // Emit to JS layer
        JSObject ret = new JSObject();
        ret.put("rawText", rawText);
        ret.put("price", price);
        ret.put("km", km);
        notifyListeners("onUberOffer", ret);

        // Also update native overlay immediately (works even with app in background)
        OverlayPlugin overlay = OverlayPlugin.getInstance();
        if (overlay != null) {
            double fuelCost = (km / FUEL_CONSUMPTION) * FUEL_PRICE;
            double platformFee = price * PLATFORM_FEE;
            double netProfit = price - fuelCost - platformFee;
            double margin = price > 0 ? (netProfit / price) * 100.0 : 0;
            double profitPerKm = km > 0 ? netProfit / km : 0;
            overlay.updateFromService(netProfit, margin, profitPerKm);
        }
    }
}

