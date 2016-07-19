package tk.mygod.speech.synthesizer

import android.app.Application
import android.content.Context
import android.support.v7.app.AppCompatDelegate

class App extends Application {
  override def onCreate {
    super.onCreate
    AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
    pref = getSharedPreferences("settings", Context.MODE_PRIVATE)
    editor = pref.edit
  }
}
