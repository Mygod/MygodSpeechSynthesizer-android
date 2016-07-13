package tk.mygod.speech

import java.text.SimpleDateFormat
import java.util.Date

import android.content.SharedPreferences

/**
  * @author Mygod
  */
package object synthesizer {
  var pref: SharedPreferences = _
  var editor: SharedPreferences.Editor = _

  def enableSsmlDroid = pref.getBoolean("text.enableSsmlDroid", false)
  def enableSsmlDroid(value: Boolean) = pref.edit.putBoolean("text.enableSsmlDroid", value).apply
  def ignoreSingleLineBreak = pref.getBoolean("text.ignoreSingleLineBreak", false)
  def lastSaveDir = pref.getString("fileSystem.lastSaveDir", null)

  var displayName: String = _
  def getSaveFileName =
    if (displayName == null) new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date) else displayName
}
