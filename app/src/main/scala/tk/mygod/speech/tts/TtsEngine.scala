package tk.mygod.speech.tts

import java.io.{File, FileOutputStream}
import java.security.InvalidParameterException

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.Spanned
import tk.mygod.speech.tts.TtsEngine.SpeechPart
import tk.mygod.text.EarconSpan

import scala.collection.immutable
import scala.collection.mutable.ArrayBuffer

/**
 * @author Mygod
 */
object TtsEngine {
  private val BEST_SPLITTERS_EVER: Int = 0
  private val SPACE_FOR_THE_BEST: Int = 1
  private val NOT_SPLITTER: Int = 6
  private val splitters = Array("!。？！…", ".?", ":;：；—", ",()[]{}，（）【】『』［］｛｝、",
    "'\"‘’“”＇＂<>＜＞《》", " \t\b\n\r\f\r\u000b\u001c\u001d\u001e\u001f\u00a0\u2028\u2029/\\|-／＼｜－")
    .zipWithIndex.flatMap { case (s, i) => s.toCharArray.map(c => (c, i)) }.toMap

  object SpeechPart {
    def parse(value: String): TtsEngine.SpeechPart = {
      val parts: Array[String] = value.split(",")
      new TtsEngine.SpeechPart(parts(0).toInt, parts(1).toInt, false)
    }
  }

  class SpeechPart(val Start: Int, val End: Int, val IsEarcon: Boolean) {
    def ==(other: TtsEngine.SpeechPart) = Start == other.Start && End == other.End && IsEarcon == other.IsEarcon
    def !=(other: TtsEngine.SpeechPart) = ! ==(other)
    def equals(other: TtsEngine.SpeechPart) = ==(other)
    override def equals(other: Any)= other match {
      case null => false
      case part: SpeechPart => ==(part)
      case _ => try ==(SpeechPart.parse(other.toString)) catch {
        case exc: Exception => false
      }
    }

    override def toString = Start + "," + End
  }
}

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

  protected def getMaxLength: Int

  protected def splitSpeech(text: CharSequence, startOffset: Int, aggressiveMode: Boolean): ArrayBuffer[SpeechPart] = {
    var i = startOffset
    val length = text.length
    val maxLength = getMaxLength
    if (maxLength <= 0) throw new InvalidParameterException("maxLength should be a positive value.")
    val result = new ArrayBuffer[SpeechPart]
    var earconsLength = 0
    var nextEarcon = length
    var earconParts: Array[SpeechPart] = null
    text match {
      case spanned: Spanned =>
        val earcons = spanned.getSpans(i, length, classOf[EarconSpan])
        earconsLength = earcons.length
        if (earconsLength > 0) {
          earconParts = new Array[SpeechPart](earconsLength)
          var j = 0
          while (j < earconsLength) {
            val span = earcons(j)
            earconParts(j) = new SpeechPart(spanned.getSpanStart(span), spanned.getSpanEnd(span), true)
            j += 1
          }
          nextEarcon = earconParts(0).Start
        }
    }
    var j = 0
    var start = -1
    var end = -1
    var maxEnd = -1
    var bestPriority = TtsEngine.NOT_SPLITTER
    var priority = TtsEngine.NOT_SPLITTER
    while (i < length) if (start < 0) if (i == nextEarcon) {
      val part = earconParts(j)
      result.append(part)
      i = part.End
      j += 1
      nextEarcon = if (j < earconsLength) earconParts(j).Start else length
    } else {
      if (!TtsEngine.splitters.contains(text.charAt(i))) {
        start = i
        end = i
        maxEnd = i + maxLength
        if (maxEnd > nextEarcon) maxEnd = nextEarcon
        bestPriority = TtsEngine.NOT_SPLITTER
        priority = TtsEngine.NOT_SPLITTER
      }
      i += 1
    } else if (i < maxEnd) {
      var next = i + 1
      var p = TtsEngine.splitters.getOrElse(text.charAt(i), TtsEngine.NOT_SPLITTER)
      if (p == TtsEngine.SPACE_FOR_THE_BEST && (next >= maxEnd || text.charAt(next).isWhitespace))
        p = TtsEngine.BEST_SPLITTERS_EVER
      if (p == TtsEngine.NOT_SPLITTER) {
        if (priority <= bestPriority) {
          end = i
          bestPriority = priority
          if (aggressiveMode && priority == TtsEngine.BEST_SPLITTERS_EVER) next = maxEnd  // break with reset
        }
        priority = TtsEngine.NOT_SPLITTER                                                 // reset
      } else if (p < priority) priority = p
      i = next
      if (i >= maxEnd) {
        if (priority <= bestPriority) end = i
        result.append(new SpeechPart(start, end, false))
        i = end
        start = -1
      }
    }
    result
  }
}
