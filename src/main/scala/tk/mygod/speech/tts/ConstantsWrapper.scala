package tk.mygod.speech.tts

import android.os.Build
import android.speech.tts.TextToSpeech

/**
 * @author Mygod
 */
object ConstantsWrapper {
  private val cls = classOf[TextToSpeech#Engine]
  def apply(fieldName: String) = cls.getDeclaredField(fieldName).get(null).toString

  val KEY_FEATURE_EMBEDDED_SYNTHESIS = apply("KEY_FEATURE_EMBEDDED_SYNTHESIS")
  val KEY_FEATURE_NETWORK_SYNTHESIS = apply("KEY_FEATURE_NETWORK_SYNTHESIS")

  val KEY_FEATURE_NETWORK_RETRIES_COUNT =
    if (Build.VERSION.SDK_INT >= 21) apply("KEY_FEATURE_NETWORK_RETRIES_COUNT") else "networkRetriesCount"
  val KEY_FEATURE_NETWORK_TIMEOUT_MS =
    if (Build.VERSION.SDK_INT >= 21) apply("KEY_FEATURE_NETWORK_TIMEOUT_MS") else "networkTimeoutMs"
  val KEY_FEATURE_NOT_INSTALLED =
    if (Build.VERSION.SDK_INT >= 21) apply("KEY_FEATURE_NOT_INSTALLED") else "notInstalled"

  val KEY_PARAM_PAN = apply("KEY_PARAM_PAN")
  val KEY_PARAM_PITCH = apply("KEY_PARAM_PITCH")
  val KEY_PARAM_RATE = apply("KEY_PARAM_RATE")
  val KEY_PARAM_UTTERANCE_ID = apply("KEY_PARAM_UTTERANCE_ID")

  // Google Text-to-speech Engine
  val KEY_FEATURE_LEGACY_SET_LANGUAGE_VOICE = "LegacySetLanguageVoice"
  // TODO: val KEY_FEATURE_SUPPORTS_VOICE_MORPHING = "SupportsVoiceMorphing"
  // TODO: val KEY_FEATURE_VOICE_MORPHING_TARGET = "VoiceMorphingTarget"
}
