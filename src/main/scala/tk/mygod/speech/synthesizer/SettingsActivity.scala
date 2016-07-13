package tk.mygod.speech.synthesizer

import android.content.Context
import android.os.Bundle
import tk.mygod.app.{CircularRevealActivity, ToolbarActivity}
import tk.mygod.content.ServicePlusConnection

/**
  * @author Mygod
  */
class SettingsActivity extends ToolbarActivity with CircularRevealActivity {
  private val connection = new ServicePlusConnection[SynthesisService]  // keep service alive
  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_settings)
    configureToolbar()
    setNavigationIcon()
    bindService(intent[SynthesisService], connection, Context.BIND_AUTO_CREATE)
  }
  override def onDestroy() {
    unbindService(connection)
    super.onDestroy()
  }
}
