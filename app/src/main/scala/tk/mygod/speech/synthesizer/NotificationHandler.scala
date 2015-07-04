package tk.mygod.speech.synthesizer

import android.content.{Context, Intent, BroadcastReceiver}

/**
 * @author Mygod
 */
final class NotificationHandler extends BroadcastReceiver {
  def onReceive(context: Context, intent: Intent) = if (intent.getAction == SynthesisService.STOP)
    context.startService(new Intent(context, classOf[SynthesisService]).putExtra("stop", true))
}
