package tk.mygod.speech.synthesizer

import java.io.FileOutputStream

import android.app.Service
import android.content.{BroadcastReceiver, Intent, IntentFilter}
import android.net.Uri
import android.os.{Handler, ParcelFileDescriptor}
import android.support.annotation.IntDef
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationCompat.Action
import android.support.v4.content.ContextCompat
import be.mygod.app.ServicePlus
import be.mygod.speech.tts.{AvailableTtsEngines, OnTtsSynthesisCallbackListener, TtsEngine}
import be.mygod.text.{SsmlDroid, TextMappings}

object SynthesisService {
  final val IDLE = 0
  final val SPEAKING = 1
  final val SYNTHESIZING = 2

  private final val ACTION_STOP = "tk.mygod.speech.synthesizer.action.STOP"

  var instance: SynthesisService = _
}

final class SynthesisService extends ServicePlus with OnTtsSynthesisCallbackListener {
  import SynthesisService._

  var engines: AvailableTtsEngines = _
  private var builder: NotificationCompat.Builder = _
  var mappings: TextMappings = _
  private var descriptor: ParcelFileDescriptor = _
  var currentText: CharSequence = _
  var rawText: String = _
  private var lastText: String = _
  var textLength: Int = _
  var prepared: Int = -1
  var currentStart: Int = -1
  var currentEnd: Int = -1
  private val handler = new Handler
  private val stopListener: BroadcastReceiver = (_, _) => stop()

  private var _inBackground: Boolean = _
  def inBackground: Boolean = _inBackground
  def inBackground(value: Boolean) {
    _inBackground = value
    if (status != IDLE) if (value) showNotification() else hideNotification()
  }

  override def onStartCommand(intent: Intent, flags: Int, startId: Int) = Service.START_NOT_STICKY
  @IntDef(Array(IDLE, SPEAKING, SYNTHESIZING))
  var status: Int = _
  var listener: OnTtsSynthesisCallbackListener = _

  override def onCreate() {
    super.onCreate()
    val engineID = App.pref.getString("engine", "")
    engines = new AvailableTtsEngines(this)
    selectEngine(engineID)
    registerReceiver(stopListener, new IntentFilter(ACTION_STOP))
    builder = new NotificationCompat.Builder(this).setContentTitle(R.string.notification_title)
      .setSmallIcon(R.drawable.ic_communication_message)
      .setColor(ContextCompat.getColor(this, R.color.material_primary_500))
      .setContentIntent(pendingIntent[MainActivity]).setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setVibrate(new Array[Long](0))
      .addAction(new Action(R.drawable.ic_av_mic_off, R.string.action_stop,
        pendingBroadcast(new Intent(ACTION_STOP).setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY))))
    instance = this
  }

  override def onDestroy() {
    engines.onDestroy()
    unregisterReceiver(stopListener)
    super.onDestroy()
    instance = null
  }

  private def showNotification(text: CharSequence = null) = if (status == IDLE) lastText = null else {
    if (text != null) {
      lastText = text.toString.replaceAll("\\s+", " ")
      builder.setContentText(lastText)
        .setTicker(if (App.pref.getBoolean("appearance.ticker", false)) lastText else null)
    }
    if (inBackground) {
      startService(intent[SynthesisService])
      startForeground(1, new NotificationCompat.BigTextStyle(builder.setWhen(System.currentTimeMillis)
        .setPriority(App.pref.getString("appearance.notificationType", "0").toInt)).bigText(lastText).build)
    }
  }
  private def hideNotification() {
    stopSelf()
    stopForeground(true)
  }

  def selectEngine(id: String) {
    stop()
    if (!engines.selectEngine(id)) return
    App.editor.putString("engine", id).apply()
    engines.selectedEngine.setVoice(App.pref.getString("engine." + id, ""))
  }

  def selectVoice(voice: String): Unit = selectVoice(engines.selectedEngine, voice)
  def selectVoice(engine: TtsEngine, voice: String) {
    engine.setVoice(voice)
    App.editor.putString("engine." + engine.getID, engine.getVoice.getName).apply()
  }

  def prepare(text: String) {
    rawText = text
    if (App.enableSsmlDroid) {
      val parser = SsmlDroid.fromSsml(text, App.ignoreSingleLineBreak, null)
      mappings = parser.Mappings
      currentText = parser.Result
    } else {
      mappings = null
      currentText = if (App.ignoreSingleLineBreak) text.replaceAll("(?<!\\n)(\\n)(?!\\n)", " ") else text
    }
    prepared = -1
    currentStart = -1
    currentEnd = -1
  }

  def speak(text: String, startOffset: Int) {
    status = SPEAKING
    prepare(text)
    engines.selectedEngine.setSynthesisCallbackListener(this)
    onTtsSynthesisStarting(currentText.length)
    engines.selectedEngine.speak(currentText, startOffset)
  }
  def synthesizeToUri(text: String, startOffset: Int, uri: Uri) {
    descriptor = getContentResolver.openFileDescriptor(uri, "w")
    synthesizeToStream(text, startOffset, new FileOutputStream(descriptor.getFileDescriptor))
  }
  def synthesizeToStream(text: String, startOffset: Int, output: FileOutputStream) {
    status = SYNTHESIZING
    prepare(text)
    engines.selectedEngine.setSynthesisCallbackListener(this)
    onTtsSynthesisStarting(currentText.length)
    engines.selectedEngine.synthesizeToStream(currentText, startOffset, output, getCacheDir)
  }
  def stop() {
    engines.selectedEngine.stop()
    onTtsSynthesisFinished()
  }

  override def onTtsSynthesisStarting(length: Int) {
    builder.setProgress(0, length, true)
    textLength = length
    engines.selectedEngine.pitch = App.pref.getInt("tweaks.pitch", 100)
    engines.selectedEngine.speechRate = App.pref.getInt("tweaks.speechRate", 100)
    engines.selectedEngine.pan = App.pref.getInt("tweaks.pan", 0) * .01F
    if (listener != null) listener.onTtsSynthesisStarting(length)
  }
  override def onTtsSynthesisPrepared(end: Int) {
    prepared = end
    if (listener != null) listener.onTtsSynthesisPrepared(end)
  }
  override def onTtsSynthesisCallback(start: Int, e: Int) {
    var end = e
    if (end < start) end = start
    builder.setProgress(textLength, start, false)
    showNotification(currentText.subSequence(start, end))
    currentStart = start
    currentEnd = end
    if (listener != null) listener.onTtsSynthesisCallback(start, end)
  }
  override def onTtsSynthesisError(s: Int, e: Int) {
    var start = s
    var end = e
    if (mappings != null) {
      start = mappings.getSourceOffset(start, preferLeft = false)
      end = mappings.getSourceOffset(end, preferLeft = true)
    }
    if (end < start) end = start
    if (start < end)
      handler.post(() => makeToast(String.format(R.string.synthesis_error, rawText.substring(start, end))).show())
    if (listener != null) listener.onTtsSynthesisError(start, end)
  }
  override def onTtsSynthesisFinished() {
    status = IDLE
    hideNotification()
    if (descriptor != null) descriptor = null
    if (listener != null) listener.onTtsSynthesisFinished()
    else stopSelf()
  }
}
