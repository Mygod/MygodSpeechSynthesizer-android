package be.mygod.speech.tts

import android.speech.tts.TextToSpeech
import be.mygod.os.Build

/**
 * @author Mygod
 */
object ConstantsWrapper {
  private val cls = classOf[TextToSpeech#Engine]
  def apply(fieldName: String): String = cls.getDeclaredField(fieldName).get(null).toString

  final val KEY_FEATURE_EMBEDDED_SYNTHESIS: String = apply("KEY_FEATURE_EMBEDDED_SYNTHESIS")
  final val KEY_FEATURE_NETWORK_SYNTHESIS: String = apply("KEY_FEATURE_NETWORK_SYNTHESIS")

  final val KEY_FEATURE_NETWORK_RETRIES_COUNT: String =
    if (Build.version >= 21) apply("KEY_FEATURE_NETWORK_RETRIES_COUNT") else "networkRetriesCount"
  final val KEY_FEATURE_NETWORK_TIMEOUT_MS: String =
    if (Build.version >= 21) apply("KEY_FEATURE_NETWORK_TIMEOUT_MS") else "networkTimeoutMs"
  final val KEY_FEATURE_NOT_INSTALLED: String = if (Build.version >= 21) apply("KEY_FEATURE_NOT_INSTALLED") else "notInstalled"

  final val KEY_PARAM_PAN: String = apply("KEY_PARAM_PAN")
  final val KEY_PARAM_PITCH: String = apply("KEY_PARAM_PITCH")
  final val KEY_PARAM_RATE: String = apply("KEY_PARAM_RATE")
  final val KEY_PARAM_UTTERANCE_ID: String = apply("KEY_PARAM_UTTERANCE_ID")

  // Google Text-to-speech Engine
  final val KEY_FEATURE_LEGACY_SET_LANGUAGE_VOICE = "LegacySetLanguageVoice"
  // Removed:
  // val KEY_FEATURE_SUPPORTS_VOICE_MORPHING = "SupportsVoiceMorphing"
  // val KEY_FEATURE_VOICE_MORPHING_TARGET = "VoiceMorphingTarget"
}
