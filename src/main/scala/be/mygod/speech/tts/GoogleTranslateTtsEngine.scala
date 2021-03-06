package be.mygod.speech.tts

import java.io._
import java.net.{HttpURLConnection, URL, URLEncoder}
import java.util.concurrent.{ArrayBlockingQueue, Semaphore}

import android.content.Context
import android.graphics.drawable.Drawable
import android.media.{AudioManager, MediaPlayer}
import android.support.v4.content.ContextCompat
import android.text.TextUtils
import be.mygod.concurrent.StoppableFuture
import be.mygod.util.Conversions._
import be.mygod.util.IOUtils
import tk.mygod.speech.synthesizer.R

import scala.collection.immutable.SortedSet
import scala.collection.{immutable, mutable}

/**
 * @author Mygod
 */
object GoogleTranslateTtsEngine {
  private val voices = Array("af", "am", "ar", "be", "bg", "bn", "bs", "ca", "chr", "cs", "cy", "da", "de", "el", "en",
                             "es", "et", "eu", "fa", "fi", "fr", "ga", "gl", "gu", "ha", "hi", "hr", "ht", "hu", "hy",
                             "id", "ig", "is", "it", "iw", "jw", "ja", "ka", "kk", "km", "kn", "ko", "ku", "ky", "lb",
                             "lo", "lt", "lv", "mg", "mi", "mk", "ml", "mn", "mr", "ms", "mt", "my", "ne", "nl", "no",
                             "ny", "or", "pa", "pl", "ps", "pt", "ro", "ru", "rw", "sd", "si", "sk", "sl", "sn", "so",
                             "sq", "sr", "st", "su", "sv", "sw", "ta", "te", "tg", "th", "tk", "tl", "tr", "tt", "ug",
                             "uk", "ur", "uz", "vi", "wo", "xh", "yi", "yo", "yue", "zu", "zh-CN", "zh-TW")
                         .map(new LocaleWrapper(_).asInstanceOf[TtsVoice]).to[immutable.SortedSet]
}

final class GoogleTranslateTtsEngine(context: Context, selfDestructionListener: TtsEngine => Unit = null)
  extends TtsEngine(context, selfDestructionListener) {
  import GoogleTranslateTtsEngine._
  private final class SpeakTask(private val currentText: CharSequence, private val startOffset: Int,
                                finished: Unit => Unit = null)
    extends StoppableFuture with MediaPlayer.OnBufferingUpdateListener {
    private val playbackQueue = new ArrayBlockingQueue[AnyRef](29)
    private val partMap = new mutable.WeakHashMap[MediaPlayer, (SpeechPart, Int)]
    private var bufferLock: Semaphore = _
    private val manager = new PlayerManager

    override def stop() {
      if (isStopped) return
      super.stop()
      manager.stop()
    }

    private class PlayerManager extends StoppableFuture(finished)
      with MediaPlayer.OnCompletionListener with MediaPlayer.OnErrorListener {
      var player: MediaPlayer = _
      private final val playLock = new Semaphore(1)

      override def stop() {
        if (isStopped) return
        super.stop()
        if (player != null) player.stop()
      }

      def work(): Unit = try {
        var obj = playbackQueue.take
        while (obj.isInstanceOf[MediaPlayer]) {
          player = obj.asInstanceOf[MediaPlayer]
          val part = partMap(player)._1
          try if (!isStopped) {
            if (listener != null) listener.onTtsSynthesisCallback(part.start, part.end)
            player.setOnCompletionListener(this)
            player.setOnErrorListener(this)
            playLock.acquire()
            player.start()
            playLock.acquire()
            playLock.release()
            if (listener != null) listener.onTtsSynthesisCallback(part.end, part.end)
          } catch {
            case e: Exception =>
              e.printStackTrace()
              if (listener != null) listener.onTtsSynthesisError(part.start, part.end)
          } finally {
            try player.stop() catch {
              case e: IllegalStateException => e.printStackTrace()
            }
            player.release()
            obj = playbackQueue.take
          }
        }
      } catch {
        case e: InterruptedException => e.printStackTrace()
      } finally if (listener != null) listener.onTtsSynthesisFinished()

      def onCompletion(mp: MediaPlayer) {
        val part = partMap(mp)._1
        if (listener != null) listener.onTtsSynthesisCallback(part.end, part.end)
        playLock.release()
      }

      def onError(mp: MediaPlayer, what: Int, extra: Int): Boolean = {
        val part = partMap(mp)._1
        if (listener != null) listener.onTtsSynthesisError(part.start, part.end)
        playLock.release()
        false
      }
    }

    def work: Unit = try {
      if (isStopped) return
      bufferLock = new Semaphore(1)
      for (part <- new SpeechSplitter(currentText, startOffset)) {
        if (isStopped) return
        var player: MediaPlayer = null
        try {
          var failed = true
          bufferLock.acquire()
          while (failed) try {
            player = new MediaPlayer()
            player.setAudioStreamType(AudioManager.STREAM_MUSIC)
            partMap.put(player, (part, 0))
            player.setOnBufferingUpdateListener(this)
            val str = currentText.subSequence(part.start, part.end).toString
            player.setDataSource(context, if (part.isEarcon) str else getUrl(str))
            player.prepare()
            failed = false
          } catch {
            case e: IOException =>
              if (e.getMessage != "Prepare failed.: status=0x1") throw e
              player.release()
              Thread.sleep(1000)
          } finally if (isStopped) return
          playbackQueue.put(player)
          bufferLock.acquire()
          bufferLock.release()
        } catch {
          case e: Exception =>
            e.printStackTrace()
            if (player != null) player.release()
            if (listener != null) listener.onTtsSynthesisError(part.start, part.end)
        }
      }
    } catch {
      case e: Exception =>
        e.printStackTrace()
        if (listener != null) listener.onTtsSynthesisError(0, currentText.length)
    } finally if (manager != null) playbackQueue.add(new AnyRef)

    def onBufferingUpdate(mp: MediaPlayer, percent: Int) {
      val (part, previous) = partMap(mp)
      if (percent <= previous) return // ignore another event triggered when playing
      partMap(mp) = (part, percent)
      if (listener != null) listener.onTtsSynthesisPrepared(part.start + part.length * percent / 100)
      if (percent >= 100 && bufferLock != null) bufferLock.release()
    }
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
        input = if (part.isEarcon) context.getContentResolver.openInputStream(str) else {
          val conn = new URL(getUrl(str)).openConnection.asInstanceOf[HttpURLConnection]
          conn.setRequestProperty("User-Agent", "stagefright/1.2 (Linux; Android 5.0)")
          // conn.setRequestProperty("Referer", "http://translate.google.com")
          //noinspection JavaAccessorMethodCalledAsEmptyParen
          conn.getInputStream()
        }
        IOUtils.copy(input, output)
        if (listener != null) listener.onTtsSynthesisCallback(part.end, part.end)
      } catch {
        case e: Exception =>
          e.printStackTrace()
          if (listener != null) listener.onTtsSynthesisError(part.start, part.end)
      } finally if (input != null) try input.close() catch {
        case e: IOException => e.printStackTrace()
      }
    } catch {
      case e: Exception =>
        e.printStackTrace()
        if (listener != null) listener.onTtsSynthesisError(0, currentText.length)
    } finally {
      try output.close() catch {
        case e: IOException => e.printStackTrace()
      }
      if (listener != null) listener.onTtsSynthesisFinished()
    }
  }

  private var voice: LocaleWrapper = new LocaleWrapper("en")
  private var speakTask: SpeakTask = _
  private var synthesizeToStreamTask: SynthesizeToStreamTask = _

  def getVoices: SortedSet[TtsVoice] = voices
  def getVoice: LocaleWrapper = voice
  def setVoice(voice: TtsVoice): Boolean = {
    val result = voices.contains(voice)
    if (result) this.voice = voice.asInstanceOf[LocaleWrapper]
    result
  }
  def setVoice(voice: String): Boolean = {
    if (TextUtils.isEmpty(voice)) return false
    for (v <- voices) if (voice == v.getName) {
      this.voice = v.asInstanceOf[LocaleWrapper]
      return true
    }
    false
  }

  override def getName: String = context.getResources.getString(R.string.google_translate_tts_engine_name)
  protected def getIconInternal: Drawable =
    try context.getPackageManager.getApplicationIcon("com.google.android.apps.translate") catch {
      case _: Exception => ContextCompat.getDrawable(context, R.drawable.ic_google_translate)
    }
  def getMimeType = "audio/mpeg"

  private def getUrl(text: String) = "https://translate.google.com/translate_tts?client=tw-ob&ie=UTF-8&tl=" +
    voice.code + "&q=" + URLEncoder.encode(text, "UTF-8") + "&ttsspeed=" + speechRate / 100.0

  def speak(text: CharSequence, startOffset: Int) {
    synthesizeToStreamTask = null
    speakTask = new SpeakTask(text, startOffset, _ => speakTask = null)
  }

  def synthesizeToStream(text: CharSequence, startOffset: Int, output: FileOutputStream, cacheDir: File) {
    speakTask = null
    synthesizeToStreamTask = new SynthesizeToStreamTask(text, startOffset, output, _ => synthesizeToStreamTask = null)
  }

  def stop() {
    if (speakTask != null) speakTask.stop()
    if (synthesizeToStreamTask != null) synthesizeToStreamTask.stop()
  }
  override def onDestroy() {
    stop()
    super.onDestroy()
  }
}
