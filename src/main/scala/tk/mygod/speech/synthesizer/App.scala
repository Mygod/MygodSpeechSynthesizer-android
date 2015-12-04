package tk.mygod.speech.synthesizer

import android.app.Application
import android.content.Context

class App extends Application {
  override def onCreate {
    super.onCreate
    pref = getSharedPreferences("settings", Context.MODE_PRIVATE)
    editor = pref.edit
  }
}
