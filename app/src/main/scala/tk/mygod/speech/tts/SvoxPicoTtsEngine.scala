package tk.mygod.speech.tts

import java.io._
import java.lang.reflect.Field
import java.lang.{Float => BoxedFloat}
import java.util
import java.util.Locale
import java.util.concurrent.{LinkedBlockingDeque, Semaphore}

import android.annotation.TargetApi
import android.content.Context
import android.net.Uri
import android.os.{AsyncTask, Build, Bundle}
import android.speech.tts.TextToSpeech.{EngineInfo, OnInitListener}
import android.speech.tts.{TextToSpeech, UtteranceProgressListener, Voice}
import android.text.TextUtils
import android.util.Log
import tk.mygod.util.UriUtils._
import tk.mygod.util.{IOUtils, LocaleUtils}

import scala.collection.JavaConversions._
import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
 * @author Mygod
 */
object SvoxPicoTtsEngine {
  private var earcons: Field = _
  private var startLock: Field = _
  try {
    val c = classOf[TextToSpeech]
    earcons = c.getDeclaredField("mEarcons")
    earcons.setAccessible(true)
    startLock = c.getDeclaredField("mStartLock")
    startLock.setAccessible(true)
  } catch {
    case e: NoSuchFieldException => e.printStackTrace
  }
}

final class SvoxPicoTtsEngine(context: Context, info: EngineInfo = null)
  extends TtsEngine(context) with OnInitListener {
  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  private final class VoiceWrapper(var voice: Voice) extends TtsVoice {
    def getFeatures = voice.getFeatures
    def getLatency = voice.getLatency
    def getLocale = voice.getLocale
    def getName = voice.getName
    def getQuality = voice.getQuality
    def isNetworkConnectionRequired = voice.isNetworkConnectionRequired
    def getDisplayName = voice.getName
    override def toString = voice.getName
  }

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  private def wrap(voice: Voice) = if (voice == null) null else new VoiceWrapper(voice)

  final class LocaleVoice(loc: Locale) extends LocaleWrapper(loc) {
    override def getFeatures = {
      val features = tts.getFeatures(locale)
      if (tts.isLanguageAvailable(getLocale) == TextToSpeech.LANG_MISSING_DATA)
        features.add(ConstantsWrapper.KEY_FEATURE_NOT_INSTALLED)
      features
    }

    override def isNetworkConnectionRequired = {
      val features = getFeatures
      features.contains(ConstantsWrapper.KEY_FEATURE_NETWORK_SYNTHESIS) &&
        !features.contains(ConstantsWrapper.KEY_FEATURE_EMBEDDED_SYNTHESIS)
    }
  }

  private final class SpeakTask extends AsyncTask[AnyRef, AnyRef, AnyRef] {
    protected def doInBackground(params: AnyRef*): AnyRef = { // note: https://issues.scala-lang.org/browse/SI-1459
      try {
        lastPart = null
        for (part <- new SpeechSplitter(currentText, startOffset, getMaxLength, true)) try {
          if (isCancelled) {
            tts.stop
            return null
          }
          val cs = currentText.subSequence(part.start, part.end)
          val id = part.toString
          if (part.isEarcon) {
            val uri = cs.toString
            tts.synchronized {
              SvoxPicoTtsEngine.earcons.get(tts).asInstanceOf[Map[String, Uri]].put(uri, uri)
            }
            if (Build.VERSION.SDK_INT >= 21) tts.playEarcon(uri, TextToSpeech.QUEUE_ADD, getParamsL(id), id)
            else tts.playEarcon(uri, TextToSpeech.QUEUE_ADD, getParams(id))
          }
          else if (Build.VERSION.SDK_INT >= 21) tts.speak(cs, TextToSpeech.QUEUE_ADD, getParamsL(id), id)
          else tts.speak(cs.toString, TextToSpeech.QUEUE_ADD, getParams(id))
          lastPart = part
          if (listener != null) listener.onTtsSynthesisPrepared(part.end)
        } catch {
          case e: Exception =>
            e.printStackTrace
            if (listener != null) listener.onTtsSynthesisError(part.start, part.end)
        }
      } catch {
        case e: Exception =>
          e.printStackTrace
          if (listener != null) listener.onTtsSynthesisError(0, currentText.length)
      }
      if (Build.VERSION.SDK_INT >= 21) tts.speak("", TextToSpeech.QUEUE_ADD, getParamsL(""), "")
      else tts.speak("", TextToSpeech.QUEUE_ADD, getParams("")) // stop sign
      null
    }
  }

  private final class SynthesizeToStreamTask extends AsyncTask[AnyRef, AnyRef, AnyRef] {
    private var output: FileOutputStream = _
    val mergeQueue = new LinkedBlockingDeque[SpeechPart]
    val synthesizeLock = new Semaphore(1)

    private def merge {
      try {
        var header: Array[Byte] = null
        var length: Long = 0
        var part = mergeQueue.take
        while (part.start >= 0) {
          var input: InputStream = null
          try {
            if (isCancelled) return
            if (listener != null) listener.onTtsSynthesisCallback(part.start, part.end)
            input = if (part.isEarcon)
              context.getContentResolver.openInputStream(currentText.subSequence(part.start, part.end))
            else new FileInputStream(part.file)
            if (header == null) {
              header = new Array[Byte](44)
              if (input.read(header, 0, 44) != 44) throw new IOException("File malformed.")
              output.write(header, 0, 44)
            } else if (input.skip(44) != 44) throw new IOException("File malformed.")
            length += IOUtils.copy(input, output)
            if (listener != null) listener.onTtsSynthesisCallback(part.end, part.end)
          } catch {
            case e: Exception =>
              e.printStackTrace
              if (listener != null) listener.onTtsSynthesisError(part.start, part.end)
          } finally {
            if (input != null) try input.close catch {
              case e: IOException => e.printStackTrace
            }
            if (part.file != null && !part.file.delete) part.file.deleteOnExit
          }
          part = mergeQueue.take
        }
        if (header != null) {
          header(40) = length.toByte
          header(41) = (length >> 8).toByte
          header(42) = (length >> 16).toByte
          header(43) = (length >> 24).toByte
          length += 36
          header(4) = length.toByte
          header(5) = (length >> 8).toByte
          header(6) = (length >> 16).toByte
          header(7) = (length >> 24).toByte
          output.getChannel.position(0)
          output.write(header, 0, 44)
        }
      } catch {
        case e: Exception =>
          e.printStackTrace
          if (listener != null) listener.onTtsSynthesisError(0, currentText.length)
      } finally {
        try output.close catch {
          case e: IOException => e.printStackTrace
        }
        if (listener != null) listener.onTtsSynthesisFinished
        synthesizeToStreamTask = null
      }
    }

    protected def doInBackground(params: AnyRef*): AnyRef = {
      var future: Future[Unit] = null
      try {
        output = params(0).asInstanceOf[FileOutputStream]
        val cacheDir = params(1).asInstanceOf[File]
        if (isCancelled) return null
        future = Future(merge)
        for (part <- new SpeechSplitter(currentText, startOffset, getMaxLength)) {
          if (isCancelled) return null
          if (!part.isEarcon) try {
            part.file = File.createTempFile(null, ".wav", cacheDir)
            synthesizeLock.acquireUninterruptibly
            val cs = currentText.subSequence(part.start, part.end)
            val id = part.toString
            if (Build.VERSION.SDK_INT >= 21) tts.synthesizeToFile(cs, getParamsL(id), part.file, id)
            else tts.synthesizeToFile(cs.toString, getParams(id), part.file.getAbsolutePath)
            synthesizeLock.acquireUninterruptibly
            synthesizeLock.release  // wait for synthesis
          } catch {
            case e: Exception =>
              e.printStackTrace
              if (listener != null) listener.onTtsSynthesisError(part.start, part.end)
          }
          mergeQueue.add(part)
        }
      } catch {
        case e: Exception =>
          e.printStackTrace
          if (listener != null) listener.onTtsSynthesisError(0, currentText.length)
      } finally if (future != null) mergeQueue.add(new SpeechPart)  // stop sign
      null
    }
  }

  private val initLock = new Semaphore(1)
  var tts: TextToSpeech = _
  private var lastPart: SpeechPart = _
  private[tts] var engineInfo: TextToSpeech.EngineInfo = _
  private var currentText: CharSequence = _
  private var voices: immutable.SortedSet[TtsVoice] = _
  private var preInitSetVoice: String = _
  private var startOffset: Int = _
  private var useNativeVoice = Build.VERSION.SDK_INT >= 21
  private var pan: BoxedFloat = null
  private var speakTask: SpeakTask = null
  private var synthesizeToStreamTask: SynthesizeToStreamTask = null

  initLock.acquireUninterruptibly
  if (info == null) tts = new TextToSpeech(context, this) else {
    engineInfo = info
    tts = new TextToSpeech(context, this, info.name)
  }
  tts.setOnUtteranceProgressListener(new UtteranceProgressListener {
    def onError(utteranceId: String) {
      if (TextUtils.isEmpty(utteranceId)) return
      val part = SpeechPart.parse(utteranceId)
      if (listener != null) listener.onTtsSynthesisError(part.start, part.end)
      if (synthesizeToStreamTask != null) synthesizeToStreamTask.synthesizeLock.release
    }

    def onDone(utteranceId: String) {
      if (TextUtils.isEmpty(utteranceId)) {
        if (listener != null) listener.onTtsSynthesisFinished
        speakTask = null
        return
      }
      val part = SpeechPart.parse(utteranceId)
      if (synthesizeToStreamTask != null) {
        synthesizeToStreamTask.synthesizeLock.release
        if (listener != null) listener.onTtsSynthesisPrepared(part.end)
      } else if (speakTask != null) if (listener != null) listener.onTtsSynthesisCallback(part.end, part.end)
    }

    def onStart(utteranceId: String) {
      if (listener == null || TextUtils.isEmpty(utteranceId)) return
      val part = SpeechPart.parse(utteranceId)
      listener.onTtsSynthesisCallback(part.start, part.end)
    }
  })

  def onInit(status: Int) {
    if (status != TextToSpeech.SUCCESS) throw new RuntimeException("SvoxPicoTtsEngine initialization failed.")
    Future {
      initVoices
      initLock.release
      if (preInitSetVoice != null) setVoice(preInitSetVoice)
      else if (useNativeVoice) {
        val voice: Voice = tts.getDefaultVoice
        if (voice != null) tts.setVoice(voice)
      }
      else setVoice(new LocaleVoice(if (Build.VERSION.SDK_INT >= 18) tts.getDefaultLanguage
        else context.getResources.getConfiguration.locale))
    }
  }

  private def initVoices {
    if (useNativeVoice) try {
      voices = tts.getVoices.map(v => wrap(v).asInstanceOf[TtsVoice]).to[immutable.SortedSet]
      return
    } catch {
      case exc: RuntimeException =>
        useNativeVoice = false
        exc.printStackTrace
        Log.e("SvoxPicoTtsEngine", "Voices not supported: " + engineInfo.name)
    }
    try voices = Locale.getAvailableLocales.filter(l => tts.isLanguageAvailable(l) != TextToSpeech.LANG_NOT_SUPPORTED)
                       .map(l => {
                         tts.setLanguage(l)
                         new LocaleVoice(tts.getLanguage).asInstanceOf[TtsVoice]
                       }).to[immutable.SortedSet]
    catch {
      case e: Exception =>
        e.printStackTrace
    }
  }

  def getVoices = {
    initLock.acquireUninterruptibly
    initLock.release
    voices
  }

  def getVoice = {
    initLock.acquireUninterruptibly
    initLock.release
    if (useNativeVoice) wrap(tts.getVoice) else new LocaleVoice(tts.getLanguage)
  }

  def setVoice(voice: TtsVoice): Boolean = {
    if (!initLock.tryAcquire) {
      preInitSetVoice = voice.getName
      return true
    }
    initLock.release
    if (useNativeVoice && voice.isInstanceOf[VoiceWrapper]) try {
      tts.setVoice(voice.asInstanceOf[VoiceWrapper].voice)
      return true
    } catch {
      case e: Exception =>
        e.printStackTrace
        return false
    }
    try {
      tts.setLanguage(voice.getLocale)
      true
    }
    catch {
      case e: Exception =>
        e.printStackTrace
        false
    }
  }

  def setVoice(voiceName: String): Boolean = {
    if (TextUtils.isEmpty(voiceName)) return false
    if (!initLock.tryAcquire) {
      preInitSetVoice = voiceName
      return true
    }
    initLock.release
    if (useNativeVoice) {
      for (voice <- voices) if (voiceName == voice.getName) return setVoice(voice)
      return false
    }
    try {
      tts.setLanguage(LocaleUtils.parseLocale(voiceName))
      true
    } catch {
      case e: Exception =>
        e.printStackTrace
        false
    }
  }

  override def getID = super.getID + ':' + engineInfo.name
  override def getName = engineInfo.label
  protected def getIconInternal = context.getPackageManager.getDrawable(engineInfo.name, engineInfo.icon, null)
  def getMimeType = "audio/x-wav"
  def getMaxLength = if (Build.VERSION.SDK_INT >= 18) TextToSpeech.getMaxSpeechInputLength else 4000

  override def setPitch(value: Float) = tts.setPitch(value)
  override def setSpeechRate(value: Float) = tts.setSpeechRate(value)
  override def setPan(value: Float) = pan = value

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  private def getParamsL(id: String) = {
    val params = new Bundle
    val voice = getVoice
    if (voice != null) {
      val features = voice.getFeatures
      if (features.contains(ConstantsWrapper.KEY_FEATURE_NETWORK_RETRIES_COUNT))
        params.putInt(ConstantsWrapper.KEY_FEATURE_NETWORK_RETRIES_COUNT, 0x7fffffff)
      if (features.contains(ConstantsWrapper.KEY_FEATURE_NETWORK_TIMEOUT_MS))
        params.putInt(ConstantsWrapper.KEY_FEATURE_NETWORK_TIMEOUT_MS, 0x7fffffff)
    }
    params.putString(ConstantsWrapper.KEY_PARAM_UTTERANCE_ID, id)
    if (pan != null) params.putFloat(ConstantsWrapper.KEY_PARAM_PAN, pan)
    params
  }

  private def getParams(id: String) = {
    val params = new util.HashMap[String, String]()
    params.put(ConstantsWrapper.KEY_PARAM_UTTERANCE_ID, id)
    if (pan != null) params.put(ConstantsWrapper.KEY_PARAM_PAN, pan.toString)
    params
  }

  def speak(text: CharSequence, startOffset: Int) {
    currentText = text
    this.startOffset = startOffset
    synthesizeToStreamTask = null
    speakTask = new SpeakTask
    speakTask.execute()
  }

  def synthesizeToStream(text: CharSequence, startOffset: Int, output: FileOutputStream, cacheDir: File) {
    currentText = text
    this.startOffset = startOffset
    speakTask = null
    synthesizeToStreamTask = new SynthesizeToStreamTask
    synthesizeToStreamTask.execute(output, cacheDir)
  }

  def stop {
    if (speakTask != null) speakTask.cancel(false)
    if (synthesizeToStreamTask != null) synthesizeToStreamTask.cancel(false)
    tts.stop
  }

  def onDestroy {
    stop
    tts.shutdown
  }
}
