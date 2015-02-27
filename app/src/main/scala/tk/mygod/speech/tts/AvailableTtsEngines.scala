package tk.mygod.speech.tts

import android.content.Context

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

/**
 * @author Mygod
 */
final class AvailableTtsEngines(context: Context) extends ArrayBuffer[TtsEngine] {
  {
    val defaultEngine: SvoxPicoTtsEngine = new SvoxPicoTtsEngine(context)
    append(defaultEngine)
    val defaultEngineName: String = defaultEngine.tts.getDefaultEngine
    for (info <- defaultEngine.tts.getEngines) if (info.name == defaultEngineName) defaultEngine.engineInfo = info
    else append(new SvoxPicoTtsEngine(context, info))
    append(new GoogleTranslateTtsEngine(context))
  }

  var selectedEngine: TtsEngine = null
  def selectEngine(id: String): Boolean = {
    for (engine <- this) if (engine.getID == id) {
      selectedEngine = engine
      return true
    }
    false
  }

  def onDestroy = for (engine <- this) engine.onDestroy
}
