package tk.mygod.speech.synthesizer

import java.io.{File, IOException, InputStream}

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.{MediaStore, OpenableColumns}
import tk.mygod.app.SaveFileFragment.SaveFileCallback
import tk.mygod.app.{FragmentStackActivity, LocationObservedActivity, SaveFileFragment}
import tk.mygod.util.IOUtils

/**
 * @author Mygod
 */
final class MainActivity extends FragmentStackActivity with LocationObservedActivity with SaveFileCallback {
  private lazy val serviceIntent = new Intent(getApplicationContext, classOf[SynthesisService])
  var settingsFragment: SettingsFragment = _

  protected override def onCreate(icicle: Bundle) {
    super.onCreate(icicle)
    setVolumeControlStream(AudioManager.STREAM_MUSIC)
    if (App.mainFragment == null) push(new MainFragment)
    startService(serviceIntent)
  }

  protected override def onStop {
    super.onStop
    SynthesisService.write(SynthesisService.instance.inBackground(true))
  }

  protected override def onStart {
    super.onStart
    if (SynthesisService.ready) SynthesisService.instance.inBackground(false)
  }

  protected override def onDestroy = {
    super.onDestroy
    SynthesisService.write(if (SynthesisService.instance.status == SynthesisService.IDLE) stopService(serviceIntent))
  }

  override def onNewIntent(data: Intent) {
    if (data == null) return
    var input: InputStream = null
    try {
      val uri = data.getData
      if (uri == null) return
      if (SynthesisService.ready && SynthesisService.instance.status != SynthesisService.IDLE) {
        App.mainFragment.makeSnackbar(R.string.error_synthesis_in_progress).show
        return
      }
      input = getContentResolver.openInputStream(uri)
      App.mainFragment.inputText.setText(IOUtils.readAllText(input))
      if ("file".equalsIgnoreCase(uri.getScheme)) App.displayName = uri.getLastPathSegment else {
        val cursor = getContentResolver.query(uri, Array(OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.TITLE),
          null, null, null)
        if (cursor != null) {
          if (cursor.moveToFirst) {
            var index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            App.displayName = cursor.getString(index)
            if (index < 0 || App.displayName == null) {
              index = cursor.getColumnIndex(MediaStore.MediaColumns.TITLE)
              App.displayName = cursor.getString(index)
            }
          }
          cursor.close
        }
      }
      if ("application/ssml+xml".equalsIgnoreCase(data.getType) || App.displayName != null &&
        App.displayName.toLowerCase.endsWith(".ssml")) App.enableSsmlDroid(true)
    } catch {
      case e: IOException =>
        e.printStackTrace
        App.mainFragment.makeSnackbar(String.format(R.string.open_error, e.getMessage))
    } finally if (input != null) try input.close catch {
      case e: IOException => e.printStackTrace
    }
  }

  def showSettings {
    if (settingsFragment == null) settingsFragment = new SettingsFragment
    settingsFragment.setSpawnLocation(getLocationOnScreen)
    push(settingsFragment)
  }

  def showSave(mimeType: String, fileName: String, requestCode: Int) = if (App.oldTimeySaveUI) {
      val fragment = new SaveFileFragment(requestCode, mimeType, App.lastSaveDir, fileName)
      fragment.setSpawnLocation(getLocationOnScreen)
      push(fragment)
    } else App.mainFragment.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
      .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, fileName).setType(mimeType), requestCode)

  def saveFilePicked(file: File, requestCode: Int) = App.mainFragment.save(Uri.fromFile(file), requestCode)
}
