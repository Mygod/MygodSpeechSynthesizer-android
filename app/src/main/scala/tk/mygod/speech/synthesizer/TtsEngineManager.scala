package tk.mygod.speech.synthesizer

import android.content.{Context, SharedPreferences}
import android.os.Build
import tk.mygod.speech.tts.{TtsEngine, AvailableTtsEngines}

/**
 * @author Mygod
 */
object TtsEngineManager {
  var engines: AvailableTtsEngines = null
  private var onSelectedEngineChangingListener: OnSelectedEngineChangingListener = null
  var pref: SharedPreferences = null
  var editor: SharedPreferences.Editor = null
  var mainActivity: MainActivity = null

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
    if (!engines.selectEngine(id)) throw new RuntimeException
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

  def getEnableSsmlDroid = pref.getBoolean("text.enableSsmlDroid", false)
  def getIgnoreSingleLineBreak = pref.getBoolean("text.ignoreSingleLineBreak", false)
  def getOldTimeySaveUI = {
    val old = Build.VERSION.SDK_INT < 19
    old || pref.getBoolean("appearance.oldTimeySaveUI", false)
  }
  def getLastSaveDir = pref.getString("fileSystem.lastSaveDir", null)
}
