package com.kapeks.turnofftimer;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class MyDeviceAdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        Toast.makeText(context, "Cihaz Yöneticisi Aktif Edildi", Toast.LENGTH_SHORT).show();
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "Cihaz Yöneticisini kapatırsanız uyku modu çalışmayabilir.";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Toast.makeText(context, "Cihaz Yöneticisi Devre Dışı", Toast.LENGTH_SHORT).show();
    }
}
