package tk.mygod.app

import java.io.File

import android.content.DialogInterface.OnClickListener
import android.content.{Context, DialogInterface, Intent}
import android.net.Uri
import android.os.{Bundle, Environment}
import android.support.v7.app.AlertDialog
import android.support.v7.widget.AppCompatEditText
import android.support.v7.widget.Toolbar.OnMenuItemClickListener
import android.view.{MenuItem, View, ViewGroup}
import android.webkit.MimeTypeMap
import android.widget._
import tk.mygod.os.Build
import tk.mygod.speech.synthesizer.{R, TR, TypedFindView}
import tk.mygod.view.LocationObserver

import scala.collection.JavaConversions._
import scala.collection.mutable

/**
  * SaveFileActivity for API level 18-.
  *
  * @author Mygod
  */
object SaveFileActivity {
  final val EXTRA_CURRENT_DIRECTORY = "tk.mygod.app.SaveFileActivity.EXTRA_CURRENT_DIRECTORY"

  private final class DirectoryDisplay(context: Context, private val content: mutable.ArrayBuffer[File])
    extends ArrayAdapter[File](context, android.R.layout.activity_list_item, android.R.id.text1, content) {
    override def getView(position: Int, convertView: View, parent: ViewGroup) = {
      var result = convertView
      if (result == null) {
        result = super.getView(position, null, parent)
        result.setOnTouchListener(LocationObserver)
      }
      val file = content(position)
      if (file != null) {
        result.findViewById(android.R.id.icon).asInstanceOf[ImageView]
          .setImageResource(if (file.isFile) R.drawable.ic_doc_generic_am_alpha else R.drawable.ic_doc_folder_alpha)
        result.findViewById(android.R.id.text1).asInstanceOf[TextView].setText(file.getName)
      }
      result
    }
  }
}

final class SaveFileActivity extends ToolbarActivity with TypedFindView with OnMenuItemClickListener {
  import SaveFileActivity._
  if (Build.version >= 19) throw new UnsupportedOperationException

  private var currentDirectory: File = _
  private var directoryDisplay: DirectoryDisplay = _
  private var fileName: AppCompatEditText = _
  private var directoryView: ListView = _

  private def setCurrentDirectory(directory: File = null) {
    if (directory != null) {
      currentDirectory = directory
      var path = currentDirectory.getAbsolutePath
      if (currentDirectory.getParent != null) path += "/"
      toolbar.setSubtitle(path)
    }
    directoryDisplay.clear
    if (currentDirectory.getParent != null) directoryDisplay.add(new File(".."))
    val list = currentDirectory.listFiles()
    if (list != null) {
      val mimeType = getIntent.getType
      val map = MimeTypeMap.getSingleton
      directoryDisplay.addAll(list.toSeq
        .filter(file => file.isDirectory || file.isFile && (mimeType == null || mimeType ==
          map.getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(file.getAbsolutePath))))
        .sortWith((lhs, rhs) => {
          var result = lhs.isFile.compareTo(rhs.isFile)
          if (result != 0) result < 0 else {
            result = lhs.getName.compareToIgnoreCase(rhs.getName)
            if (result == 0) lhs.getName < rhs.getName else result < 0
          }
        }))
    }
  }

  private def submit(v: View) = if (new File(currentDirectory, fileName.getText.toString).exists) {
    var button: Button = null
    button = new AlertDialog.Builder(this).setTitle(R.string.dialog_overwrite_confirm_title)
      .setPositiveButton(android.R.string.yes, ((_, _) => confirm(button)): OnClickListener)
      .setNegativeButton(android.R.string.no, null).show.getButton(DialogInterface.BUTTON_POSITIVE)
    button.setOnTouchListener(LocationObserver)
  } else confirm(v)

  private def confirm(v: View) {
    setResult(0, new Intent().setData(Uri.fromFile(new File(currentDirectory, fileName.getText.toString))))
    finish()
  }

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_save_file)
    configureToolbar()
    setNavigationIcon()
    toolbar.inflateMenu(R.menu.save_file_actions)
    toolbar.setOnMenuItemClickListener(this)
    fileName = findView(TR.file_name)
    directoryDisplay = new DirectoryDisplay(this, new mutable.ArrayBuffer[File])
    directoryView = findView(TR.directory_view)
    directoryView.setAdapter(directoryDisplay)
    directoryView.setOnItemClickListener((parent, view, position, id) =>
      if (position >= 0 && position < directoryDisplay.getCount) {
        val file = directoryDisplay.getItem(position)
        if (file.isFile) {
          fileName.setText(file.getName)
          submit(view)
        } else setCurrentDirectory(if (file.getName == "..") currentDirectory.getParentFile else file)
      })
    val ok = findViewById(R.id.ok)
    ok.setOnTouchListener(LocationObserver)
    ok.setOnClickListener(submit)
    setCurrentDirectory(if (savedInstanceState == null) {
      val intent = getIntent
      fileName.setText(intent.getStringExtra(Intent.EXTRA_TITLE))
      val path = intent.getStringExtra(EXTRA_CURRENT_DIRECTORY)
      if (path == null) Environment.getExternalStorageDirectory else new File(path)
    } else new File(savedInstanceState.getString(EXTRA_CURRENT_DIRECTORY)))
  }

  def onMenuItemClick(item: MenuItem) = item.getItemId match {
    case R.id.action_create_dir =>
      val text = new AppCompatEditText(this)
      new AlertDialog.Builder(this).setTitle(R.string.dialog_create_dir_title).setView(text)
        .setPositiveButton(android.R.string.ok, ((_, _) =>
          if (new File(currentDirectory, text.getText.toString).mkdirs) setCurrentDirectory()): OnClickListener)
        .setNegativeButton(android.R.string.cancel, null).show
      true
    case _ => super.onOptionsItemSelected(item)
  }

  override def onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putString(EXTRA_CURRENT_DIRECTORY, currentDirectory.getPath)
  }
}
