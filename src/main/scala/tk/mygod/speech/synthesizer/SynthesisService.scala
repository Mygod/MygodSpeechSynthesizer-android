package tk.mygod.speech.synthesizer

import java.io.FileOutputStream
import java.util.concurrent.Semaphore

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.{Handler, ParcelFileDescriptor}
import android.support.annotation.IntDef
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationCompat.Action
import android.support.v4.content.ContextCompat
import tk.mygod.concurrent.FailureHandler
import tk.mygod.content.ContextPlus
import tk.mygod.speech.tts.{AvailableTtsEngines, OnTtsSynthesisCallbackListener, TtsEngine}
import tk.mygod.text.{SsmlDroid, TextMappings}
import tk.mygod.util.Conversions._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object SynthesisService {
  val IDLE = 0
  val SPEAKING = 1
  val SYNTHESIZING = 2

  val STOP = "tk.mygod.speech.synthesizer.action.STOP"

  private var _instance: SynthesisService = _
  def instance = {
    initLock.acquireUninterruptibly
    initLock.release
    _instance
  }
  def ready = _instance != null
  private val initLock = new Semaphore(1)
  initLock.acquireUninterruptibly
  
  def read[T](action: => T, fail: PartialFunction[Throwable, Unit] = FailureHandler) =
    if (SynthesisService.ready) action else Future(action) onFailure fail
  def write[T](action: => T, fail: PartialFunction[Throwable, Unit] = FailureHandler) =
    read(SynthesisService.synchronized(action), fail)
}

final class SynthesisService extends Service with ContextPlus with OnTtsSynthesisCallbackListener {
  import SynthesisService._

  var engines: AvailableTtsEngines = _
  private var builder: NotificationCompat.Builder = _
  var mappings: TextMappings = _
  private var descriptor: ParcelFileDescriptor = _
  var currentText: CharSequence = _
  private var rawText: String = _
  private var lastText: String = _
  var textLength: Int = _
  var prepared: Int = -1
  var currentStart: Int = -1
  var currentEnd: Int = -1
  private val handler = new Handler

  private var _inBackground: Boolean = _
  def inBackground = _inBackground
  def inBackground(value: Boolean) {
    _inBackground = value
    if (status != IDLE) if (value) showNotification() else stopForeground(true)
  }

  def onBind(intent: Intent) = null
  @IntDef(Array(IDLE, SPEAKING, SYNTHESIZING))
  var status: Int = _

  override def onCreate {
    super.onCreate
    val engineID = App.pref.getString("engine", "")
    engines = new AvailableTtsEngines(this)
    selectEngine(engineID)
    builder = new NotificationCompat.Builder(this).setContentTitle(R.string.notification_title)
      .setSmallIcon(R.drawable.ic_communication_message)
      .setColor(ContextCompat.getColor(this, R.color.material_primary_500))
      .setContentIntent(pendingIntent[MainActivity]).setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setVibrate(new Array[Long](0))
      .addAction(new Action(R.drawable.ic_av_mic_off, R.string.action_stop, pendingBroadcast(STOP)))
    _instance = this
    initLock.release
  }

  override def onStartCommand(intent: Intent, flags: Int, startId: Int) = {
    if (intent != null && intent.getBooleanExtra("stop", false)) stop
    Service.START_NOT_STICKY
  }

  override def onDestroy {
    engines.onDestroy
    super.onDestroy
    _instance = null
    initLock.acquireUninterruptibly
  }

  private def showNotification(text: CharSequence = null) = if (status == IDLE) lastText = null else {
    if (text != null) {
      lastText = text.toString.replaceAll("\\s+", " ")
      builder.setContentText(lastText)
        .setTicker(if (App.pref.getBoolean("appearance.ticker", false)) lastText else null)
    }
    if (inBackground) startForeground(1, new NotificationCompat.BigTextStyle(builder
      .setWhen(System.currentTimeMillis)
      .setPriority(App.pref.getString("appearance.notificationType", "0").toInt)).bigText(lastText).build)
  }

  def selectEngine(id: String) {
    stop
    if (!engines.selectEngine(id)) return
    App.editor.putString("engine", id)
    App.editor.apply
    engines.selectedEngine.setVoice(App.pref.getString("engine." + id, ""))
  }

  def selectVoice(voice: String): Unit = selectVoice(engines.selectedEngine, voice)
  def selectVoice(engine: TtsEngine, voice: String) {
    engine.setVoice(voice)
    App.editor.putString("engine." + engine.getID, engine.getVoice.getName)
    App.editor.apply
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
  def stop {
    engines.selectedEngine.stop
    onTtsSynthesisFinished
  }

  override def onTtsSynthesisStarting(length: Int) {
    builder.setProgress(0, length, true)
    textLength = length
    engines.selectedEngine.pitch = App.pref.getInt("tweaks.pitch", 100)
    engines.selectedEngine.speechRate = App.pref.getInt("tweaks.speechRate", 100)
    engines.selectedEngine.pan = App.pref.getFloat("tweaks.pan", 0)
    if (App.mainFragment != null) App.mainFragment.onTtsSynthesisStarting(length)
  }
  override def onTtsSynthesisPrepared(end: Int) {
    prepared = end
    if (App.mainFragment != null) App.mainFragment.onTtsSynthesisPrepared(end)
  }
  override def onTtsSynthesisCallback(start: Int, e: Int) {
    var end = e
    if (end < start) end = start
    builder.setProgress(textLength, start, false)
    showNotification(currentText.subSequence(start, end))
    currentStart = start
    currentEnd = end
    if (App.mainFragment != null) App.mainFragment.onTtsSynthesisCallback(start, end)
  }
  override def onTtsSynthesisError(s: Int, e: Int) {
    var start = s
    var end = e
    instance
    if (mappings != null) {
      start = mappings.getSourceOffset(start, false)
      end = mappings.getSourceOffset(end, true)
    }
    if (end < start) end = start
    if (start < end) handler.post(showToast(String.format(R.string.synthesis_error, rawText.substring(start, end))))
    if (App.mainFragment != null) App.mainFragment.onTtsSynthesisError(start, end)
  }
  override def onTtsSynthesisFinished {
    status = IDLE
    stopForeground(true)
    if (descriptor != null) descriptor = null
    if (App.mainFragment != null) App.mainFragment.onTtsSynthesisFinished
    else if (ready) stopSelf
  }
}
