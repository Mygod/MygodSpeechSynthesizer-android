package tk.mygod.speech.synthesizer

import android.content.{Context, Intent, BroadcastReceiver}

/**
 * @author Mygod
 */
final class NotificationHandler extends BroadcastReceiver {
  def onReceive(context: Context, intent: Intent) =
    if (SynthesisService.instance != null) SynthesisService.instance.stop
}
