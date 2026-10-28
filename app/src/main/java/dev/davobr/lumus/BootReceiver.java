package dev.davobr.lumus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;

/**
 * Fire TV: Amazon's home is what boots; bring this launcher up over it and start watching Home. BOOT_COMPLETED goes
 * to ~100 apps one by one and reached this one a minute after boot, so the receiver is direct-boot aware and also
 * takes LOCKED_BOOT_COMPLETED, which few apps get. A TV has no lock screen, so the user is unlocked by then.
 */
public class BootReceiver extends BroadcastReceiver {

    /** The second of the two broadcasts can come a minute later; by then someone may be in an app. */
    private static boolean handled;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // An update kills the watcher: start it again so Home keeps landing here, and show Home now.
            HomeWatchService.start(ctx);
            ctx.startActivity(new Intent(ctx, MainActivity.class).setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) return;
        UserManager um = ctx.getSystemService(UserManager.class);
        if (um != null && !um.isUserUnlocked()) return;   // BOOT_COMPLETED follows once it is
        if (handled) return;
        handled = true;
        HomeWatchService.start(ctx, true);   // cover Amazon's home until the launcher is drawn
        ctx.startActivity(new Intent(ctx, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
