package com.xbot.xbot;

import android.app.Application;
import android.util.Log;

/**
 * Application entry point for the native Android shell.
 */
public class XBotApplication extends Application {
    private static final String TAG = "XBotApplication";
    private static XBotApplication instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.i(TAG, "XBot native shell started");
    }

    public static XBotApplication getInstance() {
        return instance;
    }
}
