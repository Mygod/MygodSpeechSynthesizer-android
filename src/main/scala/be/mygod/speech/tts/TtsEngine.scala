package be.mygod.speech.tts

import java.io.{File, FileOutputStream}

import android.content.Context
import android.graphics.drawable.Drawable

import scala.collection.immutable

/**
 * @author Mygod
 */
abstract class TtsEngine(protected var context: Context,
                         private val selfDestructionListener: TtsEngine => Unit = null) {
  def getVoices: immutable.SortedSet[TtsVoice]
  def getVoice: TtsVoice
  def setVoice(voice: TtsVoice): Boolean
  def setVoice(voice: String): Boolean

  private var icon: Drawable = _
  def getID: String = getClass.getSimpleName
  def getName: String = getID
  def getIcon: Drawable = {
    if (icon == null) icon = getIconInternal
    icon
  }
  protected def getIconInternal: Drawable

  protected var listener: OnTtsSynthesisCallbackListener = _
  def setSynthesisCallbackListener(listener: OnTtsSynthesisCallbackListener): Unit = this.listener = listener

  def getMimeType: String

  var pitch = 100
  var speechRate = 100
  var pan = 0F

  def speak(text: CharSequence, startOffset: Int)
  def synthesizeToStream(text: CharSequence, startOffset: Int, output: FileOutputStream, cacheDir: File)
  def stop()
  def onDestroy(): Unit = _destroyed = true
  private var _destroyed = false
  def destroyed: Boolean = _destroyed
}
