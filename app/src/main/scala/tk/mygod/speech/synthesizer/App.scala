package tk.mygod.speech.synthesizer

import java.text.SimpleDateFormat
import java.util.Date

import android.app.Application
import android.content.{Context, SharedPreferences}
import android.os.Build

object App {
  var pref: SharedPreferences = _
  var editor: SharedPreferences.Editor = _
  var mainFragment: MainFragment = _

  def enableSsmlDroid = pref.getBoolean("text.enableSsmlDroid", false)
  def enableSsmlDroid(value: Boolean) = pref.edit.putBoolean("text.enableSsmlDroid", value).apply
  def ignoreSingleLineBreak = pref.getBoolean("text.ignoreSingleLineBreak", false)
  def oldTimeySaveUI = {
    val old = Build.VERSION.SDK_INT < 19
    old || pref.getBoolean("appearance.oldTimeySaveUI", false)
  }
  def lastSaveDir = pref.getString("fileSystem.lastSaveDir", null)

  var displayName: String = _
  def getSaveFileName =
    if (displayName == null) new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date) else displayName
}

class App extends Application {
  override def onCreate = {
    super.onCreate
    App.pref = getSharedPreferences("settings", Context.MODE_PRIVATE)
    App.editor = App.pref.edit
  }
}
