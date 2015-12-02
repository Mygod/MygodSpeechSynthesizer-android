package tk.mygod.speech.tts

import java.security.InvalidParameterException

import android.text.Spanned
import tk.mygod.text.EarconSpan

object SpeechSplitter {
  private final val BEST_SPLITTERS_EVER = 0
  private final val SPACE_FOR_THE_BEST = 1
  private final val NOT_SPLITTER = 6
  private final val splitters = Array("!。？！…", ".?", ":;：；—", ",()[]{}，（）【】『』［］｛｝、",
    "'\"‘’“”＇＂<>＜＞《》", " \t\b\n\r\f\r\u000b\u001c\u001d\u001e\u001f\u00a0\u2028\u2029/\\|-／＼｜－")
    .zipWithIndex.flatMap { case (s, i) => s.toCharArray.map(c => (c, i)) }.toMap
}

/**
 * The lazy speech splitter.
 *
 * @author Mygod
 * @param text The text to split. Spanned is supported.
 * @param i Start offset.
 * @param maxLength Max length due to engine limits.
 * @param aggressiveMode Only used in SvoxPicoTtsEngine where sentences should be split to clarify where's being
 *                       synthesized.
 */
final class SpeechSplitter(private val text: CharSequence, private var i: Int, private val maxLength: Int = 100,
                           private val aggressiveMode: Boolean = false) extends Iterator[SpeechPart] {
  if (maxLength <= 0) throw new InvalidParameterException("maxLength should be a positive value.")
  private val len = text.length
  private var earconParts = text match {
    case spanned: Spanned => spanned.getSpans(i, len, classOf[EarconSpan])
      .map(span => new SpeechPart(spanned.getSpanStart(span), spanned.getSpanEnd(span), true)).toVector
    case _ => Vector.empty[SpeechPart]
  }
  private var nextEarcon = if (earconParts.isEmpty) len else earconParts.head.start
  private var part: SpeechPart = _
  genNext

  private def genNext {
    part = null
    var start = -1
    var end = -1
    var maxEnd = -1
    var bestPriority = SpeechSplitter.NOT_SPLITTER
    var priority = SpeechSplitter.NOT_SPLITTER
    while (i < len) if (start < 0) if (i == nextEarcon) {
      part = earconParts.head
      i = part.end
      earconParts = earconParts.tail
      nextEarcon = if (earconParts.isEmpty) len else earconParts.head.start
      return
    } else {
      if (!SpeechSplitter.splitters.contains(text.charAt(i))) {
        start = i
        end = i
        maxEnd = i + maxLength
        if (maxEnd > nextEarcon) maxEnd = nextEarcon
      }
      i += 1
    } else if (i < maxEnd) {
      var next = i + 1
      var p = SpeechSplitter.splitters.getOrElse(text.charAt(i), SpeechSplitter.NOT_SPLITTER)
      if (p == SpeechSplitter.SPACE_FOR_THE_BEST && (next >= maxEnd || text.charAt(next).isWhitespace))
        p = SpeechSplitter.BEST_SPLITTERS_EVER
      if (p == SpeechSplitter.NOT_SPLITTER) {
        if (priority <= bestPriority) {
          end = i
          bestPriority = priority
          if (aggressiveMode && priority == SpeechSplitter.BEST_SPLITTERS_EVER) next = maxEnd // break with reset
        }
        priority = SpeechSplitter.NOT_SPLITTER                                                // reset
      } else if (p < priority) priority = p
      i = next
      if (i >= maxEnd) {
        if (priority <= bestPriority) end = i
        part = new SpeechPart(start, end)
        i = end
        return
      }
    }
  }

  override def hasNext = part != null
  override def next = {
    val result = part
    genNext
    result
  }
}
