package tk.mygod.speech.synthesizer

import java.text.DecimalFormat

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.{Build, Bundle}
import android.preference.{Preference, PreferenceFragment}
import android.text.style.TextAppearanceSpan
import android.text.{SpannableStringBuilder, Spanned, TextUtils}
import tk.mygod.app.FragmentPlus
import tk.mygod.concurrent.FailureHandler
import tk.mygod.preference.IconListPreference
import tk.mygod.speech.tts.{ConstantsWrapper, LocaleWrapper, TtsEngine}
import tk.mygod.util.MethodWrappers._

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
 * @author Mygod
 */
class SettingsHolderFragment extends PreferenceFragment with FragmentPlus {
  private var engine: IconListPreference = _
  private var voice: IconListPreference = _

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    getPreferenceManager.setSharedPreferencesName("settings")
    addPreferencesFromResource(R.xml.settings)
    engine = findPreference("engine").asInstanceOf[IconListPreference]
    engine.setOnPreferenceChangeListener((preference: Preference, newValue: Any) => {
      SynthesisService.instance.selectEngine(newValue.toString)
      updateVoices()
      true
    })
    voice = findPreference("engine.voice").asInstanceOf[IconListPreference]
    voice.setOnPreferenceChangeListener((preference: Preference, newValue: Any) => {
      SynthesisService.instance.selectVoice(newValue.toString)
      voice.setSummary(SynthesisService.instance.engines.selectedEngine.getVoice.getDisplayName)
      true
    })
    SynthesisService.read {
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
        engine.init
        updateVoices()
      }
    }
    findPreference("engine.showLegacyVoices").setOnPreferenceChangeListener((preference: Preference, newValue: Any) => {
      updateVoices(Some(newValue.asInstanceOf[Boolean]))
      true
    })
    if (Build.VERSION.SDK_INT < 23)
      findPreference("text.enableSsmlDroid").setOnPreferenceChangeListener((preference: Preference, newValue: Any) => {
        App.mainFragment.styleItem.setVisible(newValue.asInstanceOf[Boolean])
        true
      })
    findPreference("ssmlDroid.userGuidelines").setOnPreferenceClickListener((preference: Preference) => {
      startActivity(new Intent(Intent.ACTION_VIEW, R.string.url_ssmldroid_user_guidelines))
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
                 ConstantsWrapper.KEY_FEATURE_LEGACY_SET_LANGUAGE_VOICE => ;
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
    } onFailure FailureHandler
  }
}
