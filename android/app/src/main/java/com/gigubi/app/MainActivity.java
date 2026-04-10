package com.gigubi.app;

import com.getcapacitor.BridgeActivity;
import java.util.ArrayList;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(GigUPlugin.class);
        registerPlugin(OverlayPlugin.class);
        super.onCreate(savedInstanceState);
    }
}

