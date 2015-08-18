package tk.mygod.speech.synthesizer

import java.io.FileOutputStream
import java.util.concurrent.Semaphore

import android.app.{NotificationManager, Service}
import android.content.{Context, Intent}
import android.net.Uri
import android.os.{PowerManager, ParcelFileDescriptor}
import android.support.annotation.IntDef
import android.support.v4.app.NotificationCompat
import tk.mygod.concurrent.FailureHandler
import tk.mygod.content.ContextPlus
import tk.mygod.speech.tts.{AvailableTtsEngines, OnTtsSynthesisCallbackListener, TtsEngine}
import tk.mygod.text.{SsmlDroid, TextMappings}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object SynthesisService {
  final val IDLE = 0
  final val SPEAKING = 1
  final val SYNTHESIZING = 2

  final val STOP = "tk.mygod.speech.synthesizer.action.STOP"

  private var _instance: SynthesisService = _
  def instance = {
    initLock.acquireUninterruptibly
    initLock.release
    _instance
  }
  def ready = _instance != null
  private val initLock = new Semaphore(1)
  initLock.acquireUninterruptibly
  
  def read[T, U](action: => T, fail: PartialFunction[Throwable, U] = FailureHandler) =
    if (SynthesisService.ready) action else Future(action) onFailure fail
  def write[T, U](action: => T, fail: PartialFunction[Throwable, U] = FailureHandler) =
    read(SynthesisService.synchronized(action), fail)
}

final class SynthesisService extends Service with ContextPlus with OnTtsSynthesisCallbackListener {
  var engines: AvailableTtsEngines = _
  private var builder: NotificationCompat.Builder = _
  var mappings: TextMappings = _
  private var descriptor: ParcelFileDescriptor = _
  var currentText: CharSequence = _
  private var rawText: String = _
  private var lastText: String = _
  private lazy val notificationManager =
    getSystemService(Context.NOTIFICATION_SERVICE).asInstanceOf[NotificationManager]
  private var wakeLock: PowerManager#WakeLock = _
  var textLength: Int = _
  var prepared: Int = -1
  var currentStart: Int = -1
  var currentEnd: Int = -1

  private var _inBackground: Boolean = _
  def inBackground = _inBackground
  def inBackground(value: Boolean) {
    _inBackground = value
    if (status != SynthesisService.IDLE) if (value) showNotification() else notificationManager.cancel(0)
  }

  def onBind(intent: Intent) = null
  @IntDef(Array(SynthesisService.IDLE, SynthesisService.SPEAKING, SynthesisService.SYNTHESIZING))
  var status: Int = _

  override def onCreate {
    wakeLock = getSystemService(Context.POWER_SERVICE).asInstanceOf[PowerManager]
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SynthesisService")
    wakeLock.acquire
    super.onCreate
    val engineID = App.pref.getString("engine", "")
    engines = new AvailableTtsEngines(this)
    selectEngine(engineID)
    builder = new NotificationCompat.Builder(this).setContentTitle(R.string.notification_title).setAutoCancel(true)
      .setSmallIcon(R.drawable.ic_communication_message).setColor(getResources.getColor(R.color.material_purple_500))
      .setContentIntent(pendingIntent[MainActivity]).setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setVibrate(new Array[Long](0))
      .setDeleteIntent(pendingIntentBroadcast(SynthesisService.STOP))
    SynthesisService._instance = this
    SynthesisService.initLock.release
  }

  override def onStartCommand(intent: Intent, flags: Int, startId: Int) = {
    if (intent != null && intent.getBooleanExtra("stop", false)) stop
    Service.START_STICKY
  }

  override def onDestroy {
    engines.onDestroy
    super.onDestroy
    SynthesisService._instance = null
    SynthesisService.initLock.acquireUninterruptibly
    wakeLock.release
  }

  private def showNotification(text: CharSequence = null) = if (status == SynthesisService.IDLE) lastText = null else {
    if (text != null) {
      lastText = text.toString.replaceAll("\\s+", " ")
      builder.setContentText(lastText)
        .setTicker(if (App.pref.getBoolean("appearance.ticker", false)) lastText else null)
    }
    if (inBackground) notificationManager.notify(0, new NotificationCompat.BigTextStyle(builder
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
    status = SynthesisService.SPEAKING
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
    status = SynthesisService.SYNTHESIZING
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
    engines.selectedEngine.pan = App.pref.getString("tweaks.pan", "0").toFloat
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
    if (SynthesisService.instance.mappings != null) {
      start = SynthesisService.instance.mappings.getSourceOffset(start, false)
      end = SynthesisService.instance.mappings.getSourceOffset(end, true)
    }
    if (end < start) end = start
    if (start < end) showToast(String.format(R.string.synthesis_error, rawText.substring(start, end)))
    if (App.mainFragment != null) App.mainFragment.onTtsSynthesisError(start, end)
  }
  override def onTtsSynthesisFinished {
    status = SynthesisService.IDLE
    notificationManager.cancel(0)
    if (descriptor != null) descriptor = null
    if (App.mainFragment != null) App.mainFragment.onTtsSynthesisFinished
    else if (SynthesisService.ready) stopSelf
  }
}
