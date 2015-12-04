package tk.mygod.speech.synthesizer

import java.io.{File, IOException}

import android.Manifest.permission
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.{MediaStore, OpenableColumns}
import android.support.v13.app.FragmentCompat.OnRequestPermissionsResultCallback
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import tk.mygod.app.SaveFileFragment.SaveFileCallback
import tk.mygod.app.{FragmentStackActivity, LocationObservedActivity, SaveFileFragment}
import tk.mygod.os.Build
import tk.mygod.util.CloseUtils._
import tk.mygod.util.IOUtils

/**
 * @author Mygod
 */
object MainActivity {
  private val PERMISSION_REQUEST_STORAGE = 0
}

final class MainActivity extends FragmentStackActivity with LocationObservedActivity with SaveFileCallback
  with OnRequestPermissionsResultCallback {
  import MainActivity._

  private lazy val serviceIntent = intent[SynthesisService]
  var settingsFragment: SettingsFragment = _
  private var pendingUri: Uri = _

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

  protected override def onDestroy {
    super.onDestroy
    SynthesisService.write(if (SynthesisService.instance.status == SynthesisService.IDLE) stopService(serviceIntent))
  }

  private def canReadExtStorage =
    ContextCompat.checkSelfPermission(this, permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

  private def processUri(uri: Uri) = if (canReadExtStorage) autoClose(getContentResolver.openInputStream(uri)) {
    input =>
      App.mainFragment.inputText.setText(IOUtils.readAllText(input))
      App.displayName = uri.getLastPathSegment
    } else showToast(R.string.read_file_storage_denied)

  override def onNewIntent(data: Intent) {
    super.onNewIntent(data)
    if (data != null) try {
      val uri = data.getData
      if (uri == null) return
      if (SynthesisService.ready && SynthesisService.instance.status != SynthesisService.IDLE) {
        App.mainFragment.makeSnackbar(R.string.error_synthesis_in_progress).show
        return
      }
      if ("file".equals(uri.getScheme)) if (canReadExtStorage) processUri(uri) else {
        pendingUri = uri
        ActivityCompat.requestPermissions(this, Array(permission.READ_EXTERNAL_STORAGE), PERMISSION_REQUEST_STORAGE)
      } else {
        autoClose(getContentResolver.openInputStream(uri))(stream =>
          App.mainFragment.inputText.setText(IOUtils.readAllText(stream)))
        autoClose(getContentResolver.query(uri, Array(OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.TITLE),
          null, null, null))(cursor => if (cursor != null && cursor.moveToFirst) {
          var index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          App.displayName = cursor.getString(index)
          if (index < 0 || App.displayName == null) {
            index = cursor.getColumnIndex(MediaStore.MediaColumns.TITLE)
            App.displayName = cursor.getString(index)
          }
        })
      }
      if ("application/ssml+xml".equalsIgnoreCase(data.getType) || App.displayName != null &&
        App.displayName.toLowerCase.endsWith(".ssml")) App.enableSsmlDroid(true)
    } catch {
      case e: IOException =>
        e.printStackTrace
        App.mainFragment.makeSnackbar(String.format(R.string.open_error, e.getMessage))
    }
  }

  override def onRequestPermissionsResult(requestCode: Int, permissions: Array[String], grantResults: Array[Int]) =
    requestCode match {
      case PERMISSION_REQUEST_STORAGE => if (pendingUri != null) {
        processUri(pendingUri)
        pendingUri = null
      }
      case _ => if (Build.version >= 23) super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

  def showSettings {
    if (settingsFragment == null) settingsFragment = new SettingsFragment
    settingsFragment.setSpawnLocation(getLocationOnScreen)
    push(settingsFragment)
  }

  def showSave(mimeType: String, fileName: String, requestCode: Int) = if (Build.version < 19) {
      val fragment = new SaveFileFragment(requestCode, mimeType, App.lastSaveDir, fileName)
      fragment.setSpawnLocation(getLocationOnScreen)
      push(fragment)
    } else App.mainFragment.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
      .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, fileName).setType(mimeType), requestCode)

  def saveFilePicked(file: File, requestCode: Int) = App.mainFragment.save(Uri.fromFile(file), requestCode)
}
