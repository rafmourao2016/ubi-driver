package com.gigubi.app;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

public class GigUNotificationListener extends NotificationListenerService {
    private static final String TAG = "GigUNotif";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (pkg == null) return;

        // Filtra apenas 99 e Uber
        boolean is99 = pkg.contains("app99") || pkg.contains("taxis");
        boolean isUber = pkg.contains("ubercab");

        if (!is99 && !isUber) return;

        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text = extras.getString(Notification.EXTRA_TEXT, "");
        String bigText = extras.getString(Notification.EXTRA_BIG_TEXT, "");

        String fullContent = (title + " " + text + " " + bigText).trim();
        
        Log.d(TAG, "Notificação de " + pkg + ": " + fullContent);

        if (!fullContent.isEmpty()) {
            GigUPlugin plugin = GigUPlugin.getInstance();
            if (plugin != null) {
                // Passa para o processamento centralizado
                plugin.processRawText(fullContent, pkg, "Notification");
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Opcional: tratar remoção
    }
}
