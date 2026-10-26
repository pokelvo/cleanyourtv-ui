package io.cleanyourtv.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * PackageInstaller's answers for an update from the NAS (Updates.install): shows Android's "Update this app?" screen,
 * or says why it failed. On success this app is replaced: UpdatedReceiver runs in the new version.
 */
public class InstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try {
                    c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    AppLog.w("install: can't show the confirmation: " + e);
                }
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            AppLog.w("install: status " + status + " " + why);
            if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {   // aborted: the viewer said no
                android.widget.Toast.makeText(c, Ui.tr("A atualização não foi instalada.", "The update wasn't installed.")
                        + (why != null ? " (" + why + ")" : ""), android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }
}
