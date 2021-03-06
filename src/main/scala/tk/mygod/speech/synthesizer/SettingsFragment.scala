package tk.mygod.speech.synthesizer

import java.text.DecimalFormat

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.support.v7.preference.Preference
import android.text.style.TextAppearanceSpan
import android.text.{SpannableStringBuilder, Spanned, TextUtils}
import be.mygod.app.ActivityPlus
import be.mygod.concurrent.FailureHandler
import be.mygod.preference._
import be.mygod.speech.tts.{ConstantsWrapper, LocaleWrapper, TtsEngine}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
 * @author Mygod
 */
final class SettingsFragment extends PreferenceFragmentPlus {
  private var engine: IconListPreference = _
  private var voice: IconListPreference = _

  def onCreatePreferences(savedInstanceState: Bundle, rootKey: String) {
    getPreferenceManager.setSharedPreferencesName("settings")
    addPreferencesFromResource(R.xml.settings)
    engine = findPreference("engine").asInstanceOf[IconListPreference]
    engine.setOnPreferenceChangeListener((_, newValue) => {
      SynthesisService.instance.selectEngine(newValue.toString)
      updateVoices()
      true
    })
    voice = findPreference("engine.voice").asInstanceOf[IconListPreference]
    voice.setOnPreferenceChangeListener((_, newValue) => {
      SynthesisService.instance.selectVoice(newValue.toString)
      voice.setSummary(SynthesisService.instance.engines.selectedEngine.getVoice.getDisplayName)
      true
    })
    val count = SynthesisService.instance.engines.size
    val names = new Array[CharSequence](count)
    val ids = new Array[CharSequence](count)
    val icons = new Array[Drawable](count)
    for (i <- 0 until count) {
      val te: TtsEngine = SynthesisService.instance.engines(i)
      names(i) = te.getName
      ids(i) = te.getID
      icons(i) = te.getIcon
    }
    runOnUiThread {
      engine.setEntries(names)
      engine.setEntryValues(ids)
      engine.setEntryIcons(icons)
      engine.setValue(SynthesisService.instance.engines.selectedEngine.getID)
      engine.init()
      updateVoices()
    }
    findPreference("engine.showLegacyVoices").setOnPreferenceChangeListener((_, newValue) => {
      updateVoices(Some(newValue.asInstanceOf[Boolean]))
      true
    })
    findPreference("ssmlDroid.userGuidelines").setOnPreferenceClickListener(_ => {
      getActivity.asInstanceOf[ActivityPlus].launchUrl(R.string.url_ssmldroid_user_guidelines)
      true
    })
  }

  private def updateVoices(legacy: Option[Boolean] = None) {
    voice.setEntries(null)
    voice.setEntryValues(null)
    voice.setValue(null)
    voice.setSummary(null)
    Future {
      val voices = SynthesisService.instance.engines.selectedEngine.getVoices
      val count = voices.size
      val names = new ArrayBuffer[CharSequence](count)
      val ids = new ArrayBuffer[CharSequence](count)
      val showLegacy = legacy getOrElse App.pref.getBoolean("engine.showLegacyVoices", false)
      for (voice <- voices) {
        val features = voice.getFeatures
        if (showLegacy || !features.contains(ConstantsWrapper.KEY_FEATURE_LEGACY_SET_LANGUAGE_VOICE)) {
          val builder = new SpannableStringBuilder
          builder.append(voice.getDisplayName)
          val start = builder.length
          val df = new DecimalFormat("0.#")
          if (!voice.isInstanceOf[LocaleWrapper]) builder.append(String.format(R.string.settings_voice_information,
            voice.getLocale.getDisplayName, String.format(R.string.settings_quality, df.format(voice.getQuality / 5D)),
            String.format(R.string.settings_latency, df.format(voice.getLatency / 5D))))
          var unsupportedFeatures: CharSequence = ""
          for (feature <- features) feature match {
            case ConstantsWrapper.KEY_FEATURE_EMBEDDED_SYNTHESIS | ConstantsWrapper.KEY_FEATURE_NETWORK_SYNTHESIS |
                 ConstantsWrapper.KEY_FEATURE_NETWORK_RETRIES_COUNT | ConstantsWrapper.KEY_FEATURE_NETWORK_TIMEOUT_MS |
                 ConstantsWrapper.KEY_FEATURE_LEGACY_SET_LANGUAGE_VOICE =>
            case ConstantsWrapper.KEY_FEATURE_NOT_INSTALLED =>
              builder.append(getText(R.string.settings_voice_information_not_installed))
            case _ =>
              if (TextUtils.isEmpty(unsupportedFeatures))
                unsupportedFeatures = R.string.settings_voice_information_unsupported_features
              else unsupportedFeatures += ", "
              unsupportedFeatures += feature
          }
          if (voice.isNetworkConnectionRequired)
            builder.append(getText(R.string.settings_voice_information_network_connection_required))
          builder.append(unsupportedFeatures)
          if (builder.length != start) builder.setSpan(new TextAppearanceSpan(getActivity,
            android.R.style.TextAppearance_Small), start + 1, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
          names.append(builder)
          ids.append(voice.getName)
        }
      }
      val v = SynthesisService.instance.engines.selectedEngine.getVoice
      runOnUiThread {
        voice.setEntries(names.toArray)
        voice.setEntryValues(ids.toArray)
        if (v != null) {
          voice.setValue(v.getName)
          voice.setSummary(v.getDisplayName)
        }
        voice.init
      }
    } onComplete FailureHandler
  }

  override def onDisplayPreferenceDialog(preference: Preference): Unit = if (preference.getKey == "engine")
    displayPreferenceDialog(preference.getKey, new BottomSheetPreferenceDialogFragment())
  else super.onDisplayPreferenceDialog(preference)
}
