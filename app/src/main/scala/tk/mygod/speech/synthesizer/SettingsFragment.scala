package tk.mygod.speech.synthesizer

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.preference.{Preference, PreferenceFragment}
import android.speech.tts.Voice
import android.text.{Spanned, SpannableStringBuilder}
import android.text.style.TextAppearanceSpan
import tk.mygod.app.FragmentPlus
import tk.mygod.preference.IconListPreference
import tk.mygod.speech.tts.{ConstantsWrapper, LocaleWrapper, TtsEngine}
import tk.mygod.util.MethodWrappers._

/**
 * @author Mygod
 */

class SettingsFragment extends PreferenceFragment with FragmentPlus {
  private var engine: IconListPreference = null
  private var voice: IconListPreference = null

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    getPreferenceManager.setSharedPreferencesName("settings")
    addPreferencesFromResource(R.xml.settings)
    engine = findPreference("engine").asInstanceOf[IconListPreference]
    engine.setOnPreferenceChangeListener((preference: Preference, newValue: Any) => {
      TtsEngineManager.selectEngine(newValue.toString)
      updateVoices
      true
    })
    voice = findPreference("engine.voice").asInstanceOf[IconListPreference]
    voice.setOnPreferenceChangeListener((preference: Preference, newValue: Any) => {
      TtsEngineManager.selectVoice(newValue.toString)
      voice.setSummary(TtsEngineManager.engines.selectedEngine.getVoice.getDisplayName)
      true
    })
    val count = TtsEngineManager.engines.size
    val names = new Array[CharSequence](count)
    val ids = new Array[CharSequence](count)
    val icons = new Array[Drawable](count)
    for (i <- 0 until count) {
      val te: TtsEngine = TtsEngineManager.engines(i)
      names(i) = te.getName
      ids(i) = te.getID
      icons(i) = te.getIcon
    }
    engine.setEntries(names)
    engine.setEntryValues(ids)
    engine.setEntryIcons(icons)
    engine.setValue(TtsEngineManager.engines.selectedEngine.getID)
    engine.init
    updateVoices
    findPreference("ssmlDroid.userGuidelines").setOnPreferenceClickListener((preference: Preference) => {
      startActivity(new Intent(Intent.ACTION_VIEW, R.string.url_ssmldroid_user_guidelines))
      true
    })
  }

  private def updateVoices {
    val voices = TtsEngineManager.engines.selectedEngine.getVoices
    val count = voices.size
    val names = new Array[CharSequence](count)
    val ids = new Array[CharSequence](count)
    var i = 0
    for (voice <- voices) {
      val builder = new SpannableStringBuilder
      builder.append(voice.getDisplayName)
      val start = builder.length
      val features = voice.getFeatures
      if (!voice.isInstanceOf[LocaleWrapper]) builder.append(String.format(R.string.settings_voice_information,
        voice.getLocale.getDisplayName, qualityFormat(voice.getQuality), latencyFormat(voice.getLatency)))
      var first = true
      var notInstalled = false
      for (feature <- features) if (ConstantsWrapper.KEY_FEATURE_NOT_INSTALLED == feature) notInstalled = true
      else if (ConstantsWrapper.KEY_FEATURE_EMBEDDED_SYNTHESIS != feature &&
        ConstantsWrapper.KEY_FEATURE_NETWORK_SYNTHESIS != feature &&
        ConstantsWrapper.KEY_FEATURE_NETWORK_RETRIES_COUNT != feature &&
        ConstantsWrapper.KEY_FEATURE_NETWORK_TIMEOUT_MS != feature) {
        builder.append(if (first) {
          first = false
          getText(R.string.settings_voice_information_unsupported_features)
        } else ", ")
        builder.append(feature)
      }
      if (notInstalled) builder.append(getText(R.string.settings_voice_information_not_installed))
      if (voice.isNetworkConnectionRequired)
        builder.append(getText(R.string.settings_voice_information_network_connection_required))
      if (builder.length != start) builder.setSpan(new TextAppearanceSpan(getActivity,
        android.R.style.TextAppearance_Small), start + 1, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      names(i) = builder
      ids(i) = voice.getName
      i += 1
    }
    voice.setEntries(names)
    voice.setEntryValues(ids)
    val v = TtsEngineManager.engines.selectedEngine.getVoice
    if (v == null) {
      voice.setValue(null)
      voice.setSummary(null)
    } else {
      voice.setValue(v.getName)
      voice.setSummary(v.getDisplayName)
    }
    voice.init
  }

  private def latencyFormat(latency: Int): CharSequence = {
    latency match {
      case Voice.LATENCY_VERY_LOW => getText(R.string.settings_latency_very_low)
      case Voice.LATENCY_LOW => getText(R.string.settings_latency_low)
      case Voice.LATENCY_NORMAL => getText(R.string.settings_latency_normal)
      case Voice.LATENCY_HIGH => getText(R.string.settings_latency_high)
      case Voice.LATENCY_VERY_HIGH => getText(R.string.settings_latency_very_high)
      case _ => String.format(R.string.settings_latency, latency: Integer)
    }
  }

  private def qualityFormat(quality: Int): CharSequence = {
    quality match {
      case Voice.QUALITY_VERY_LOW => getText(R.string.settings_quality_very_low)
      case Voice.QUALITY_LOW => getText(R.string.settings_quality_low)
      case Voice.QUALITY_NORMAL => getText(R.string.settings_quality_normal)
      case Voice.QUALITY_HIGH => getText(R.string.settings_quality_high)
      case Voice.QUALITY_VERY_HIGH => getText(R.string.settings_quality_very_high)
      case _ => String.format(R.string.settings_quality, quality: Integer)
    }
  }
}