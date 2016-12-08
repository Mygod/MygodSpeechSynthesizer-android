package be.mygod.speech.tts

import android.content.Context

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

/**
 * @author Mygod
 */
final class AvailableTtsEngines(context: Context) extends ArrayBuffer[TtsEngine] {
  var selectedEngine: TtsEngine = {
    def appendIfValid(engine: TtsEngine) = if (!engine.destroyed) append(engine)
    def selfDestructionDetected(engine: TtsEngine) = {
      val index = indexOf(engine)
      if (index >= 0) remove(index)
    }
    val defaultEngine: SvoxPicoTtsEngine = new SvoxPicoTtsEngine(context, null, selfDestructionDetected)
    if (!defaultEngine.destroyed) {
      append(defaultEngine)
      val defaultEngineName = defaultEngine.tts.getDefaultEngine
      for (info <- defaultEngine.tts.getEngines) if (info.name == defaultEngineName) defaultEngine.engineInfo = info
        else appendIfValid(new SvoxPicoTtsEngine(context, info, selfDestructionDetected))
    }
    appendIfValid(new GoogleTranslateTtsEngine(context, selfDestructionDetected))
    head
  }

  def selectEngine(id: String): Boolean = {
    for (engine <- this) if (engine.getID == id) {
      selectedEngine = engine
      return true
    }
    false
  }

  def onDestroy(): Unit = for (engine <- this) engine.onDestroy()
}
