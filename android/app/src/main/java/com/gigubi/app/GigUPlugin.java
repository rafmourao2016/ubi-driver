package com.gigubi.app;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "GigUPlugin")
public class GigUPlugin extends Plugin {
    private static GigUPlugin instance;

    @Override
    public void load() {
        super.load();
        instance = this;
    }

    public static GigUPlugin getInstance() {
        return instance;
    }

    public void emitOfferReceived(String rawText, double price, double km) {
        JSObject ret = new JSObject();
        ret.put("rawText", rawText);
        ret.put("price", price);
        ret.put("km", km);
        notifyListeners("onUberOffer", ret);
    }
}
