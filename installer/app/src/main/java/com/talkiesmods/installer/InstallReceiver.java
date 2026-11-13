package com.talkiesmods.installer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Android's installer answers here: it needs you to confirm, or it's done, or it refused. */
public class InstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String last;
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                try {
                    if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    Toast.makeText(c, "Android's installer didn't open", Toast.LENGTH_LONG).show();
                }
                return;
            case PackageInstaller.STATUS_SUCCESS:
                last = "Installed " + intent.getStringExtra("version") + ". Press Open Talkies.";
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                last = "Android refused: another Talkies, signed with a different key, is installed. "
                        + "Back up in Talkies, press Uninstall Talkies, then Install.";
                break;
            default:
                last = "Not installed" + (msg == null ? "" : " (" + msg + ")");
        }
        c.getSharedPreferences("mods", Context.MODE_PRIVATE).edit().putString("last", last).apply();
        Toast.makeText(c, last, Toast.LENGTH_LONG).show();
    }
}
