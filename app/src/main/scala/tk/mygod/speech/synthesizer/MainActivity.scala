package tk.mygod.speech.synthesizer

import java.io.{IOException, InputStream}

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.{MediaStore, OpenableColumns}
import android.support.v4.app.NotificationCompat
import tk.mygod.app.{FragmentStackActivity, LocationObservedActivity, SaveFileFragment}
import tk.mygod.util.IOUtils

/**
 * @author Mygod
 */
final class MainActivity extends FragmentStackActivity with LocationObservedActivity {
  lazy val mainFragment = new MainFragment
  private lazy val settingsFragment = new SettingsFragment
  var builder: NotificationCompat.Builder = _

  protected override def onCreate(icicle: Bundle) {
    super.onCreate(icicle)
    setVolumeControlStream(AudioManager.STREAM_MUSIC)
    push(mainFragment)
    builder = new NotificationCompat.Builder(this).setContentTitle(R.string.notification_title).setAutoCancel(true)
      .setSmallIcon(R.drawable.ic_communication_message).setColor(getResources.getColor(R.color.material_purple_500))
      .setContentIntent(pendingIntent[MainActivity]).setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setDeleteIntent(pendingIntentBroadcast("tk.mygod.speech.synthesizer.action.STOP"))
    val intent = getIntent
    if (intent.getData != null) onNewIntent(intent)
  }

  override def onNewIntent(data: Intent) {
    if (data == null) return
    var input: InputStream = null
    try {
      val uri = data.getData
      if (uri == null) return
      if (mainFragment.status != MainFragment.IDLE) {
        showToast(R.string.error_synthesis_in_progress)
        return
      }
      input = getContentResolver.openInputStream(uri)
      mainFragment.inputText.setText(IOUtils.readAllText(input))
      if ("file".equalsIgnoreCase(uri.getScheme)) mainFragment.displayName = uri.getLastPathSegment
      else {
        val cursor = getContentResolver.query(uri, Array(OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.TITLE),
          null, null, null)
        if (cursor != null) {
          if (cursor.moveToFirst) {
            var index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            mainFragment.displayName = cursor.getString(index)
            if (index < 0 || mainFragment.displayName == null) {
              index = cursor.getColumnIndex(MediaStore.MediaColumns.TITLE)
              mainFragment.displayName = cursor.getString(index)
            }
          }
          cursor.close
        }
      }
    } catch {
      case e: IOException =>
        e.printStackTrace
        showToast(String.format(R.string.open_error, e.getMessage))
    } finally if (input != null) try input.close catch {
      case e: IOException => e.printStackTrace
    }
  }

  def showSettings {
    settingsFragment.setSpawnLocation(getLocationOnScreen)
    push(settingsFragment)
  }

  def showSave(mimeType: String, fileName: String, requestCode: Int) = if (TtsEngineManager.getOldTimeySaveUI) {
      val fragment = new SaveFileFragment(file => mainFragment.save(Uri.fromFile(file), requestCode), mimeType,
        TtsEngineManager.getLastSaveDir, fileName)
      fragment.setSpawnLocation(getLocationOnScreen)
      push(fragment)
    } else mainFragment.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
      .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, fileName).setType(mimeType), requestCode)
}
