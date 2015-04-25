package tk.mygod.speech.synthesizer

import android.content.{Context, SharedPreferences}
import android.os.Build
import tk.mygod.speech.tts.{TtsEngine, AvailableTtsEngines}

/**
 * @author Mygod
 */
object TtsEngineManager {
  var engines: AvailableTtsEngines = _
  private var onSelectedEngineChangingListener: OnSelectedEngineChangingListener = _
  var pref: SharedPreferences = _
  var editor: SharedPreferences.Editor = _
  var mainActivity: MainActivity = _

  def init(context: MainActivity, listener: OnSelectedEngineChangingListener) {
    pref = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val engineID = pref.getString("engine", "")
    mainActivity = context
    engines = new AvailableTtsEngines(context)
    editor = pref.edit
    selectEngine(engineID)
    onSelectedEngineChangingListener = listener
  }

  def destroy {
    onSelectedEngineChangingListener = null
    engines.onDestroy
  }

  def selectEngine(id: String) {
    if (onSelectedEngineChangingListener != null) onSelectedEngineChangingListener.onSelectedEngineChanging
    if (!engines.selectEngine(id)) return
    editor.putString("engine", id)
    editor.apply
    engines.selectedEngine.setVoice(pref.getString("engine." + id, ""))
  }

  def selectVoice(voice: String): Unit = selectVoice(engines.selectedEngine, voice)
  def selectVoice(engine: TtsEngine, voice: String) {
    engine.setVoice(voice)
    editor.putString("engine." + engine.getID, engine.getVoice.getName)
    editor.apply
  }

  def enableSsmlDroid = pref.getBoolean("text.enableSsmlDroid", false)
  def enableSsmlDroid(value: Boolean) = pref.edit.putBoolean("text.enableSsmlDroid", value).apply
  def ignoreSingleLineBreak = pref.getBoolean("text.ignoreSingleLineBreak", false)
  def oldTimeySaveUI = {
    val old = Build.VERSION.SDK_INT < 19
    old || pref.getBoolean("appearance.oldTimeySaveUI", false)
  }
  def lastSaveDir = pref.getString("fileSystem.lastSaveDir", null)
}
