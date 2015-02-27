package tk.mygod.speech.tts

import java.util.Locale

import tk.mygod.util.LocaleUtils

import scala.collection.mutable

/**
 * @author Mygod
 */
class LocaleWrapper extends TtsVoice {
  protected final var locale: Locale = null
  final var code: String = null

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

  def getFeatures = mutable.Set.empty[String]
  def getLatency = 300
  def getLocale = locale
  def getName = code
  def getQuality = 300
  def isNetworkConnectionRequired = true
  def getDisplayName = locale.getDisplayName
}
