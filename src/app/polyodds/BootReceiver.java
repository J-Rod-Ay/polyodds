package app.polyodds;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Bring the phone remote + auto-detect back after a reboot. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        OverlayService.start(c, OverlayService.cmd(c, "boot"));
    }
}
