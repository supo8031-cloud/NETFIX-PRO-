package com.netfixpro.app;

import com.getcapacitor.BridgeActivity;
import com.netfixpro.app.WifiToolsPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(WifiToolsPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
