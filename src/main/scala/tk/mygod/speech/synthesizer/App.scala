package tk.mygod.speech.synthesizer

import java.text.SimpleDateFormat
import java.util.Date

import android.content.{Context, SharedPreferences}
import android.support.v7.app.AppCompatDelegate
import be.mygod.app.ApplicationPlus

class App extends ApplicationPlus {
  import App._

  override def onCreate {
    super.onCreate
    AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
    pref = getSharedPreferences("settings", Context.MODE_PRIVATE)
    editor = pref.edit
    try editor.putInt("tweaks.pan", (pref.getFloat("tweaks.pan", 0) * 100).toInt) catch {
      case _: Exception =>
    }
  }
}

object App {
  var pref: SharedPreferences = _
  var editor: SharedPreferences.Editor = _

  def enableSsmlDroid: Boolean = pref.getBoolean("text.enableSsmlDroid", false)
  def enableSsmlDroid(value: Boolean): Unit = pref.edit.putBoolean("text.enableSsmlDroid", value).apply()
  def ignoreSingleLineBreak: Boolean = pref.getBoolean("text.ignoreSingleLineBreak", false)
  def lastSaveDir: String = pref.getString("fileSystem.lastSaveDir", null)

  var displayName: String = _
  def getSaveFileName: String =
    if (displayName == null) new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date) else displayName
}
