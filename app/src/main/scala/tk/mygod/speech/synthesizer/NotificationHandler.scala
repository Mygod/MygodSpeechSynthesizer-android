package tk.mygod.speech.synthesizer

import android.content.{Context, Intent, BroadcastReceiver}

/**
 * @author Mygod
 */
final class NotificationHandler extends BroadcastReceiver {
  def onReceive(context: Context, intent: Intent) =
    if (TtsEngineManager.mainActivity != null && ("tk.mygod.speech.synthesizer.action.STOP" == intent.getAction))
      TtsEngineManager.mainActivity.stopSynthesis
}
