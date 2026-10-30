package io.github.coloros.mihomebridge;

import android.app.Application;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class BridgeApplication extends Application {
    static volatile XposedService framework;
    @Override public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override public void onServiceBind(XposedService service) { framework = service; }
            @Override public void onServiceDied(XposedService service) { framework = null; }
        });
    }
}
