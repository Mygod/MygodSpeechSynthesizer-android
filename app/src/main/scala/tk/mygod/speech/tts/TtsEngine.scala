package tk.mygod.speech.tts

import java.io.{File, FileOutputStream}

import android.content.Context
import android.graphics.drawable.Drawable

import scala.collection.immutable

/**
 * @author Mygod
 */
abstract class TtsEngine(protected var context: Context) {
  def getVoices: immutable.SortedSet[TtsVoice]
  def getVoice: TtsVoice
  def setVoice(voice: TtsVoice): Boolean
  def setVoice(voice: String): Boolean

  private var icon: Drawable = null
  def getID: String = getClass.getSimpleName
  def getName: String = getID
  def getIcon: Drawable = {
    if (icon == null) icon = getIconInternal
    icon
  }
  protected def getIconInternal: Drawable

  protected var listener: OnTtsSynthesisCallbackListener = null
  def setSynthesisCallbackListener(listener: OnTtsSynthesisCallbackListener) = this.listener = listener

  def getMimeType: String

  def setPitch(value: Float) = ()
  def setSpeechRate(value: Float) = ()
  def setPan(value: Float) = ()

  def speak(text: CharSequence, startOffset: Int)
  def synthesizeToStream(text: CharSequence, startOffset: Int, output: FileOutputStream, cacheDir: File)
  def stop
  def onDestroy
}
