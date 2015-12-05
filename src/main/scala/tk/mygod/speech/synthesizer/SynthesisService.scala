package tk.mygod.speech.synthesizer

import java.io.{FileOutputStream, IOException}
import java.text.DateFormat
import java.util.Calendar

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.{Handler, ParcelFileDescriptor}
import android.support.annotation.IntDef
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationCompat.Action
import android.support.v4.content.ContextCompat
import android.util.Log
import tk.mygod.CurrentApp
import tk.mygod.app.ServicePlus
import tk.mygod.speech.tts.{AvailableTtsEngines, OnTtsSynthesisCallbackListener, TtsEngine}
import tk.mygod.text.{SsmlDroid, TextMappings}
import tk.mygod.util.Conversions._
import tk.mygod.util.IOUtils

object SynthesisService {
  final val IDLE = 0
  final val SPEAKING = 1
  final val SYNTHESIZING = 2

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

  private var _inBackground: Boolean = _
  def inBackground = _inBackground
  def inBackground(value: Boolean) {
    _inBackground = value
    if (status != IDLE) if (value) showNotification() else hideNotification
  }

  override def onStartCommand(intent: Intent, flags: Int, startId: Int) = Service.START_NOT_STICKY
  @IntDef(Array(IDLE, SPEAKING, SYNTHESIZING))
  var status: Int = _

  private def formatDefaultText(pattern: String) = {
    val calendar = Calendar.getInstance
    val buildTime = CurrentApp.getBuildTime(this)
    calendar.setTime(buildTime)
    String.format(pattern, CurrentApp.getVersionName(this),
      DateFormat.getDateInstance(DateFormat.FULL).format(buildTime),
      DateFormat.getTimeInstance(DateFormat.FULL).format(buildTime), calendar.get(Calendar.YEAR): Integer,
      calendar.get(Calendar.MONTH): Integer, calendar.get(Calendar.DAY_OF_MONTH): Integer,
      calendar.get(Calendar.DAY_OF_WEEK): Integer, calendar.get(Calendar.HOUR_OF_DAY): Integer,
      calendar.get(Calendar.MINUTE): Integer)
  }
  override def onCreate {
    super.onCreate
    val engineID = pref.getString("engine", "")
    engines = new AvailableTtsEngines(this)
    selectEngine(engineID)
    builder = new NotificationCompat.Builder(this).setContentTitle(R.string.notification_title)
      .setSmallIcon(R.drawable.ic_communication_message)
      .setColor(ContextCompat.getColor(this, R.color.material_primary_500))
      .setContentIntent(pendingIntent[MainActivity]).setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setVibrate(new Array[Long](0))
      .addAction(new Action(R.drawable.ic_av_mic_off, R.string.action_stop,
        pendingBroadcast("tk.mygod.speech.synthesizer.action.STOP")))
    if (enableSsmlDroid)
      try rawText = formatDefaultText(IOUtils.readAllText(getResources.openRawResource(R.raw.input_text_default)))
      catch {
        case e: IOException => e.printStackTrace
      }
    if (rawText == null) rawText = formatDefaultText(R.string.input_text_default)
    instance = this
  }

  override def onDestroy {
    engines.onDestroy
    super.onDestroy
    instance = null
  }

  private def showNotification(text: CharSequence = null) = if (status == IDLE) lastText = null else {
    if (text != null) {
      lastText = text.toString.replaceAll("\\s+", " ")
      builder.setContentText(lastText)
        .setTicker(if (pref.getBoolean("appearance.ticker", false)) lastText else null)
    }
    if (inBackground) {
      startService(intent[SynthesisService])
      startForeground(1, new NotificationCompat.BigTextStyle(builder.setWhen(System.currentTimeMillis)
        .setPriority(pref.getString("appearance.notificationType", "0").toInt)).bigText(lastText).build)
    }
  }
  private def hideNotification {
    stopSelf
    stopForeground(true)
  }

  def selectEngine(id: String) {
    stop
    if (!engines.selectEngine(id)) return
    editor.putString("engine", id)
    editor.apply
    engines.selectedEngine.setVoice(pref.getString("engine." + id, ""))
  }

  def selectVoice(voice: String): Unit = selectVoice(engines.selectedEngine, voice)
  def selectVoice(engine: TtsEngine, voice: String) {
    engine.setVoice(voice)
    editor.putString("engine." + engine.getID, engine.getVoice.getName)
    editor.apply
  }

  def prepare(text: String) {
    rawText = text
    if (enableSsmlDroid) {
      val parser = SsmlDroid.fromSsml(text, ignoreSingleLineBreak, null)
      mappings = parser.Mappings
      currentText = parser.Result
    } else {
      mappings = null
      currentText = if (ignoreSingleLineBreak) text.replaceAll("(?<!\\n)(\\n)(?!\\n)", " ") else text
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
    engines.selectedEngine.pitch = pref.getInt("tweaks.pitch", 100)
    engines.selectedEngine.speechRate = pref.getInt("tweaks.speechRate", 100)
    engines.selectedEngine.pan = pref.getFloat("tweaks.pan", 0)
    if (mainFragment != null) mainFragment.onTtsSynthesisStarting(length)
  }
  override def onTtsSynthesisPrepared(end: Int) {
    prepared = end
    if (mainFragment != null) mainFragment.onTtsSynthesisPrepared(end)
  }
  override def onTtsSynthesisCallback(start: Int, e: Int) {
    var end = e
    if (end < start) end = start
    builder.setProgress(textLength, start, false)
    showNotification(currentText.subSequence(start, end))
    currentStart = start
    currentEnd = end
    if (mainFragment != null) mainFragment.onTtsSynthesisCallback(start, end)
  }
  override def onTtsSynthesisError(s: Int, e: Int) {
    var start = s
    var end = e
    if (mappings != null) {
      start = mappings.getSourceOffset(start, false)
      end = mappings.getSourceOffset(end, true)
    }
    if (end < start) end = start
    if (start < end) handler.post(showToast(String.format(R.string.synthesis_error, rawText.substring(start, end))))
    if (mainFragment != null) mainFragment.onTtsSynthesisError(start, end)
  }
  override def onTtsSynthesisFinished {
    status = IDLE
    hideNotification
    if (descriptor != null) descriptor = null
    if (mainFragment != null) mainFragment.onTtsSynthesisFinished
    else stopSelf
  }
}
