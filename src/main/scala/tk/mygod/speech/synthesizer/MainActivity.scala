package tk.mygod.speech.synthesizer

import java.io.{File, IOException}

import android.Manifest.permission
import android.content.pm.PackageManager
import android.content.{ComponentName, Context, Intent}
import android.media.AudioManager
import android.net.Uri
import android.os.{Bundle, IBinder}
import android.provider.{MediaStore, OpenableColumns}
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import tk.mygod.app.SaveFileFragment.SaveFileCallback
import tk.mygod.app.{FragmentStackActivity, LocationObservedActivity, SaveFileFragment}
import tk.mygod.content.ServicePlusConnection
import tk.mygod.os.Build
import tk.mygod.util.CloseUtils._
import tk.mygod.util.IOUtils

/**
 * @author Mygod
 */
object MainActivity {
  private final val PERMISSION_REQUEST_STORAGE = 0
}

final class MainActivity extends FragmentStackActivity with LocationObservedActivity with SaveFileCallback {
  import MainActivity._

  private lazy val serviceIntent = intent[SynthesisService]
  val connection: ServicePlusConnection[SynthesisService] = new ServicePlusConnection[SynthesisService] {
    override def onServiceConnected(name: ComponentName, binder: IBinder) {
      super.onServiceConnected(name, binder)
      if (service.get.status != SynthesisService.IDLE) {
        mainFragment.inputText.setText(service.get.rawText)
        mainFragment.textView.setText(service.get.rawText)
      }
      if (service.get.status == SynthesisService.IDLE) mainFragment.onTtsSynthesisFinished else {
        mainFragment.onTtsSynthesisStarting(service.get.currentText.length)
        if (service.get.prepared >= 0) mainFragment.onTtsSynthesisPrepared(service.get.prepared)
        if (service.get.currentStart >= 0)
          mainFragment.onTtsSynthesisCallback(service.get.currentStart, service.get.currentEnd)
      }
    }
  }
  var settingsFragment: SettingsFragment = _
  private var pendingUri: Uri = _

  protected override def onCreate(icicle: Bundle) {
    super.onCreate(icicle)
    setVolumeControlStream(AudioManager.STREAM_MUSIC)
    if (mainFragment == null) push(new MainFragment)
    bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
  }

  protected override def onStop {
    super.onStop
    connection.service match {
      case Some(service) => service.inBackground(true)
      case _ =>
    }
  }

  protected override def onStart {
    super.onStart
    connection.service match {
      case Some(service) => service.inBackground(false)
      case _ =>
    }
  }

  protected override def onDestroy {
    super.onDestroy
    unbindService(connection)
  }

  private def canReadExtStorage =
    ContextCompat.checkSelfPermission(this, permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

  private def processUri(uri: Uri) = if (canReadExtStorage) autoClose(getContentResolver.openInputStream(uri)) {
    input =>
      mainFragment.inputText.setText(IOUtils.readAllText(input))
      displayName = uri.getLastPathSegment
    } else makeToast(R.string.read_file_storage_denied).show

  override def onNewIntent(data: Intent) {
    super.onNewIntent(data)
    if (data != null) try {
      val uri = data.getData
      if (uri == null) return
      if ((connection.service match {
        case Some(service) => service.status
        case _ => SynthesisService.IDLE
      }) != SynthesisService.IDLE) {
        mainFragment.makeSnackbar(R.string.error_synthesis_in_progress).show
        return
      }
      if ("file".equals(uri.getScheme)) if (canReadExtStorage) processUri(uri) else {
        pendingUri = uri
        ActivityCompat.requestPermissions(this, Array(permission.READ_EXTERNAL_STORAGE), PERMISSION_REQUEST_STORAGE)
      } else {
        autoClose(getContentResolver.openInputStream(uri))(stream =>
          mainFragment.inputText.setText(IOUtils.readAllText(stream)))
        autoClose(getContentResolver.query(uri, Array(OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.TITLE),
          null, null, null))(cursor => if (cursor != null && cursor.moveToFirst) {
          var index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          displayName = cursor.getString(index)
          if (index < 0 || displayName == null) {
            index = cursor.getColumnIndex(MediaStore.MediaColumns.TITLE)
            displayName = cursor.getString(index)
          }
        })
      }
      if ("application/ssml+xml".equalsIgnoreCase(data.getType) || displayName != null &&
        displayName.toLowerCase.endsWith(".ssml")) enableSsmlDroid(true)
    } catch {
      case e: IOException =>
        e.printStackTrace
        mainFragment.makeSnackbar(String.format(R.string.open_error, e.getMessage))
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
    val fragment = if (settingsFragment == null) new SettingsFragment else settingsFragment
    fragment.setSpawnLocation(getLocationOnScreen)
    push(fragment)
  }

  def showSave(mimeType: String, fileName: String, requestCode: Int) = if (Build.version < 19) {
      val fragment = new SaveFileFragment(requestCode, mimeType, lastSaveDir, fileName)
      fragment.setSpawnLocation(getLocationOnScreen)
      push(fragment)
    } else mainFragment.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
      .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, fileName).setType(mimeType), requestCode)

  def saveFilePicked(file: File, requestCode: Int) = mainFragment.save(Uri.fromFile(file), requestCode)
}
