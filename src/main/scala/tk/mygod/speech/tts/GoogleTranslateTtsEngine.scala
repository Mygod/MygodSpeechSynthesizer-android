package tk.mygod.speech.tts

import java.io._
import java.net.{URL, URLEncoder}
import java.util.concurrent.{ArrayBlockingQueue, Semaphore}

import android.content.Context
import android.media.{AudioManager, MediaPlayer}
import android.support.v4.content.ContextCompat
import android.text.TextUtils
import tk.mygod.concurrent.StoppableFuture
import tk.mygod.speech.synthesizer.R
import tk.mygod.util.IOUtils
import tk.mygod.util.Conversions._

import scala.collection.{immutable, mutable}

/**
 * @author Mygod
 */
object GoogleTranslateTtsEngine {
  private val voices = Array("af", "sq", "ar", "hy", "bs", "ca", "zh-CN", "zh-TW", "hr", "cs", "da", "nl", "en", "eo",
                             "fi", "fr", "de", "el", "ht", "hi", "hu", "is", "id", "it", "ja", "la", "lv", "mk", "no",
                             "pl", "pt", "ro", "ru", "sr", "sk", "es", "sw", "sv", "ta", "th", "tr", "vi", "cy")
                         .map(new LocaleWrapper(_).asInstanceOf[TtsVoice]).to[immutable.SortedSet]
}

final class GoogleTranslateTtsEngine(context: Context, selfDestructionListener: TtsEngine => Unit = null)
  extends TtsEngine(context, selfDestructionListener) {
  private final class SpeakTask(private val currentText: CharSequence, private val startOffset: Int,
                                finished: Unit => Unit = null) extends StoppableFuture(finished) {
    private val playbackQueue = new ArrayBlockingQueue[AnyRef](29)
    private val partMap = new mutable.HashMap[MediaPlayer, SpeechPart]
    private val manager = new PlayerManager

    override def stop {
      if (isStopped) return
      super.stop
      manager.stop
    }

    private class PlayerManager extends StoppableFuture
      with MediaPlayer.OnCompletionListener with MediaPlayer.OnErrorListener {
      var player: MediaPlayer = _
      private final val playLock = new Semaphore(1)

      override def stop {
        if (isStopped) return
        super.stop
        if (player != null) player.stop
      }

      def work: Unit = try {
        var obj = playbackQueue.take
        while (obj.isInstanceOf[MediaPlayer]) {
          player = obj.asInstanceOf[MediaPlayer]
          val part = partMap(player)
          try if (!isStopped) {
            if (listener != null) listener.onTtsSynthesisCallback(part.start, part.end)
            player.setOnCompletionListener(this)
            player.setOnErrorListener(this)
            playLock.acquireUninterruptibly
            player.start
            playLock.acquireUninterruptibly
            playLock.release
            if (listener != null) listener.onTtsSynthesisCallback(part.end, part.end)
          } catch {
            case e: Exception =>
              e.printStackTrace
              if (listener != null) listener.onTtsSynthesisError(part.start, part.end)
          } finally {
            try player.stop catch {
              case e: IllegalStateException => e.printStackTrace
            }
            player.release
            obj = playbackQueue.take
          }
        }
      } catch {
        case e: InterruptedException => e.printStackTrace
      } finally if (listener != null) listener.onTtsSynthesisFinished

      def onCompletion(mp: MediaPlayer) {
        val part = partMap(mp)
        if (listener != null) listener.onTtsSynthesisCallback(part.end, part.end)
        playLock.release
      }

      def onError(mp: MediaPlayer, what: Int, extra: Int) = {
        val part = partMap(mp)
        if (listener != null) listener.onTtsSynthesisError(part.start, part.end)
        playLock.release
        false
      }
    }

    def work: Unit = try {
      if (isStopped) return
      for (part <- new SpeechSplitter(currentText, startOffset)) {
        if (isStopped) return
        var player: MediaPlayer = null
        try {
          var failed = true
          while (failed) try {
            player = new MediaPlayer
            player.setAudioStreamType(AudioManager.STREAM_MUSIC)
            val str = currentText.subSequence(part.start, part.end).toString
            player.setDataSource(if (part.isEarcon) str else getUrl(str))
            player.prepare
            failed = false
          } catch {
            case e: IOException =>
              if (!("Prepare failed.: status=0x1" == e.getMessage)) throw e
              player.release
              Thread.sleep(1000)
          }
          if (isStopped) return
          partMap.put(player, part)
          playbackQueue.put(player)
          if (listener != null) listener.onTtsSynthesisPrepared(part.end)
        } catch {
          case e: Exception =>
            e.printStackTrace
            if (player != null) player.release
            if (listener != null) listener.onTtsSynthesisError(part.start, part.end)
        }
      }
    } catch {
      case e: Exception =>
        e.printStackTrace
        if (listener != null) listener.onTtsSynthesisError(0, currentText.length)
    } finally if (manager != null) playbackQueue.add(new AnyRef)
  }

  private class SynthesizeToStreamTask(private val currentText: CharSequence, private val startOffset: Int,
                                       private val output: OutputStream, finished: Unit => Unit = null)
    extends StoppableFuture(finished) {
    def work: Unit = try for (part <- new SpeechSplitter(currentText, startOffset)) {
      if (isStopped) return
      var input: InputStream = null
      try {
        if (listener != null) listener.onTtsSynthesisCallback(part.start, part.end)
        val str = currentText.subSequence(part.start, part.end).toString
        input = if (part.isEarcon) context.getContentResolver.openInputStream(str)
          else new URL(getUrl(str)).openStream()
        IOUtils.copy(input, output)
        if (listener != null) listener.onTtsSynthesisCallback(part.end, part.end)
      } catch {
        case e: Exception =>
          e.printStackTrace
          if (listener != null) listener.onTtsSynthesisError(part.start, part.end)
      } finally if (input != null) try input.close catch {
        case e: IOException => e.printStackTrace
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
    }
  }

  private var voice: LocaleWrapper = new LocaleWrapper("en")
  private var speakTask: SpeakTask = null
  private var synthesizeToStreamTask: SynthesizeToStreamTask = null

  def getVoices = GoogleTranslateTtsEngine.voices
  def getVoice = voice
  def setVoice(voice: TtsVoice) = {
    val result = GoogleTranslateTtsEngine.voices.contains(voice)
    if (result) this.voice = voice.asInstanceOf[LocaleWrapper]
    result
  }
  def setVoice(voice: String): Boolean = {
    if (TextUtils.isEmpty(voice)) return false
    for (v <- GoogleTranslateTtsEngine.voices) if (voice == v.getName) {
      this.voice = v.asInstanceOf[LocaleWrapper]
      return true
    }
    false
  }

  override def getName = context.getResources.getString(R.string.google_translate_tts_engine_name)
  protected def getIconInternal = try context.getPackageManager.getApplicationIcon("com.google.android.apps.translate")
    catch {
      case e: Exception => ContextCompat.getDrawable(context, R.drawable.ic_google_translate)
    }
  def getMimeType = "audio/mpeg"

  private def getUrl(text: String) =
    "https://translate.google.com/translate_tts?ie=UTF-8&tl=" + voice.code + "&q=" + URLEncoder.encode(text, "UTF-8")

  def speak(text: CharSequence, startOffset: Int) {
    synthesizeToStreamTask = null
    speakTask = new SpeakTask(text, startOffset, _ => speakTask = null)
  }

  def synthesizeToStream(text: CharSequence, startOffset: Int, output: FileOutputStream, cacheDir: File) {
    speakTask = null
    synthesizeToStreamTask = new SynthesizeToStreamTask(text, startOffset, output, _ => synthesizeToStreamTask = null)
  }

  def stop {
    if (speakTask != null) speakTask.stop
    if (synthesizeToStreamTask != null) synthesizeToStreamTask.stop
  }
  override def onDestroy {
    stop
    super.onDestroy
  }
}
