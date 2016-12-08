package be.mygod.speech.tts

import java.io.File

/**
 * @author Mygod
 */
object SpeechPart {
  def parse(value: String): SpeechPart = {
    val parts = value.split(",")
    new SpeechPart(parts(0).toInt, parts(1).toInt)
  }
}

final class SpeechPart(val start: Int = -1, val end: Int = -1, val isEarcon: Boolean = false) {
  import SpeechPart._

  lazy val length: Int = end - start

  var file: File = _
  
  def equals(other: SpeechPart): Boolean = start == other.start && end == other.end && isEarcon == other.isEarcon
  override def equals(other: Any): Boolean = other match {
    case null => false
    case part: SpeechPart => equals(part)
    case _ => try equals(parse(other.toString)) catch {
      case _: Exception => false
    }
  }

  override def toString: String = start + "," + end
}
