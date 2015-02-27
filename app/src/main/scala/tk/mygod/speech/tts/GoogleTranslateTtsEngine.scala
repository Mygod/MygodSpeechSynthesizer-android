package tk.mygod.speech.tts

import java.io._
import java.net.{URL, URLEncoder}
import java.security.InvalidParameterException
import java.util.concurrent.{ArrayBlockingQueue, Semaphore}

import android.content.Context
import android.media.{AudioManager, MediaPlayer}
import android.os.AsyncTask
import android.text.TextUtils
import tk.mygod.speech.synthesizer.R
import tk.mygod.util.IOUtils
import tk.mygod.util.UriUtils._

import scala.collection.{immutable, mutable}

/**
 * @author Mygod
 */
object GoogleTranslateTtsEngine {
  private[tts] val voices = Array("af", "sq", "ar", "hy", "bs", "ca", "zh-CN", "zh-TW", "hr", "cs", "da", "nl", "en",
                                  "eo", "fi", "fr", "de", "el", "ht", "hi", "hu", "is", "id", "it", "ja", "la", "lv",
                                  "mk", "no", "pl", "pt", "ro", "ru", "sr", "sk", "es", "sw", "sv", "ta", "th", "tr",
                                  "vi", "cy").map(lang => new LocaleWrapper(lang).asInstanceOf[TtsVoice])
                              .to[immutable.SortedSet]
}

final class GoogleTranslateTtsEngine(context: Context) extends TtsEngine(context) {
  private final class SpeakTask extends AsyncTask[AnyRef, AnyRef, AnyRef] {
    private val playbackQueue = new ArrayBlockingQueue[AnyRef](29)
    private val partMap = new mutable.HashMap[MediaPlayer, TtsEngine.SpeechPart]()
    private var playThread: PlayerThread = _

    def stop {
      if (isCancelled) return
      cancel(false)
      if (playThread == null || playThread.player == null) return
      if (playThread.player.isPlaying) playThread.player.stop
    }

    private class PlayerThread extends Thread with MediaPlayer.OnCompletionListener with MediaPlayer.OnErrorListener {
      var player: MediaPlayer = _
      private final val playLock = new Semaphore(1)

      override def run {
        try {
          var obj = playbackQueue.take
          while (obj.isInstanceOf[MediaPlayer]) {
            player = obj.asInstanceOf[MediaPlayer]
            val part = partMap(player)
            try if (!isCancelled) {
              if (listener != null) listener.onTtsSynthesisCallback(part.Start, part.End)
              player.setOnCompletionListener(this)
              player.setOnErrorListener(this)
              playLock.acquireUninterruptibly
              player.start
              playLock.acquireUninterruptibly
              playLock.release
              if (listener != null) listener.onTtsSynthesisCallback(part.End, part.End)
            } catch {
              case e: Exception =>
                e.printStackTrace
                if (listener != null) listener.onTtsSynthesisError(part.Start, part.End)
            } finally {
              try player.stop catch {
                case e: IllegalStateException => e.printStackTrace
              }
              player.release
              obj = playbackQueue.take
            }
          }
          speakTask = null
        } catch {
          case e: InterruptedException => e.printStackTrace
        }
        if (listener != null) listener.onTtsSynthesisFinished
      }

      def onCompletion(mp: MediaPlayer) {
        val part = partMap(mp)
        if (listener != null) listener.onTtsSynthesisCallback(part.End, part.End)
        playLock.release
      }

      def onError(mp: MediaPlayer, what: Int, extra: Int) = {
        val part = partMap(mp)
        if (listener != null) listener.onTtsSynthesisError(part.Start, part.End)
        playLock.release
        false
      }
    }

    protected def doInBackground(params: AnyRef*): AnyRef = { // note: https://issues.scala-lang.org/browse/SI-1459
      try {
        val parts = splitSpeech(currentText, startOffset, false)
        if (isCancelled) return null
        playThread = new PlayerThread
        playThread.start
        for (part <- parts) {
          if (isCancelled) return null
          var player: MediaPlayer = null
          try {
            var failed = true
            while (failed) try {
              player = new MediaPlayer
              player.setAudioStreamType(AudioManager.STREAM_MUSIC)
              val str: String = currentText.subSequence(part.Start, part.End).toString
              player.setDataSource(if (part.IsEarcon) str else getUrl(str))
              player.prepare
              failed = false
            } catch {
              case e: IOException =>
                if (!("Prepare failed.: status=0x1" == e.getMessage)) throw e
                player.release
                Thread.sleep(1000)
            }
            if (isCancelled) return null
            partMap.put(player, part)
            playbackQueue.put(player)
            if (listener != null) listener.onTtsSynthesisPrepared(part.End)
          } catch {
            case e: Exception =>
              e.printStackTrace
              if (player != null) player.release
              if (listener != null) listener.onTtsSynthesisError(part.Start, part.End)
          }
        }
      } catch {
        case e: Exception =>
          e.printStackTrace
          if (listener != null) listener.onTtsSynthesisError(0, currentText.length)
      } finally if (playThread != null) playbackQueue.add(new AnyRef)
      null
    }
  }

  private class SynthesizeToStreamTask extends AsyncTask[AnyRef, AnyRef, AnyRef] {
    protected def doInBackground(params: AnyRef*): AnyRef = {
      if (params.length != 1 && !params(0).isInstanceOf[OutputStream])
        throw new InvalidParameterException("Params incorrect.")
      val output = params(0).asInstanceOf[OutputStream]
      try for (part <- splitSpeech(currentText, startOffset, false)) {
        if (isCancelled) return null
        var input: InputStream = null
        try {
          if (listener != null) listener.onTtsSynthesisCallback(part.Start, part.End)
          val str = currentText.subSequence(part.Start, part.End).toString
          input = if (part.IsEarcon) context.getContentResolver.openInputStream(str)
            else new URL(getUrl(str)).openStream
          IOUtils.copy(input, output)
          if (listener != null) listener.onTtsSynthesisCallback(part.End, part.End)
        } catch {
          case e: Exception =>
            e.printStackTrace
            if (listener != null) listener.onTtsSynthesisError(part.Start, part.End)
        } finally if (input != null) try input.close catch {
          case e: IOException => e.printStackTrace
        }
      } catch {
        case e: Exception =>
          e.printStackTrace
          if (listener != null) listener.onTtsSynthesisError(0, currentText.length)
      } finally try output.close catch {
        case e: IOException => e.printStackTrace
      }
      null
    }

    protected override def onPostExecute(arg: AnyRef) {
      synthesizeToStreamTask = null
      if (listener != null) listener.onTtsSynthesisFinished
    }
  }

  private var voice: LocaleWrapper = null
  private var currentText: CharSequence = null
  private var startOffset: Int = 0
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
      case e: Exception => context.getResources.getDrawable(R.drawable.ic_google_translate)
    }
  def getMimeType = "audio/mpeg"
  protected def getMaxLength = 100

  private def getUrl(text: String) =
    "https://translate.google.com/translate_tts?ie=UTF-8&tl=" + voice.code + "&q=" + URLEncoder.encode(text, "UTF-8")

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
    synthesizeToStreamTask = new SynthesizeToStreamTask()
    synthesizeToStreamTask.execute(output)
  }

  def stop {
    if (speakTask != null) speakTask.stop
    if (synthesizeToStreamTask != null) synthesizeToStreamTask.cancel(false)
  }
  def onDestroy = stop
}
