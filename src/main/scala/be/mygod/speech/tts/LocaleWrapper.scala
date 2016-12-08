package be.mygod.speech.tts

import java.util.Locale

import be.mygod.util.LocaleUtils

import scala.collection.mutable

/**
 * @author Mygod
 */
class LocaleWrapper extends TtsVoice {
  protected final var locale: Locale = _
  final var code: String = _

  private[tts] def this(loc: Locale) {
    this()
    locale = loc
    code = loc.toString
  }
  private[tts] def this(code: String) {
    this()
    this.code = code
    locale = LocaleUtils.parseLocale(code)
  }

  def getFeatures: mutable.Set[String] = mutable.Set.empty[String]
  def getLatency = 300
  def getLocale: Locale = locale
  def getName: String = code
  def getQuality = 300
  def isNetworkConnectionRequired = true
  def getDisplayName: String = locale.getDisplayName
}
