package be.mygod.speech.tts

import java.util.Locale

import scala.collection.mutable

/**
 * @author Mygod
 */
abstract class TtsVoice extends Ordered[TtsVoice] {
  def getFeatures: mutable.Set[String]
  def getLatency: Int
  def getLocale: Locale
  def getName: String
  def getQuality: Int
  def isNetworkConnectionRequired: Boolean
  def getDisplayName: String

  override def compare(that: TtsVoice): Int = {
    val lang = getLocale.getDisplayName.compareToIgnoreCase(that.getLocale.getDisplayName)
    if (lang == 0) getName.compareToIgnoreCase(that.getName) else lang
  }
}
