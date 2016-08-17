package tk.mygod.speech.synthesizer

import java.io.{IOException, OutputStream}
import java.net.{URI, URISyntaxException}
import java.text.{DateFormat, NumberFormat}
import java.util.{Calendar, Locale}

import android.Manifest.permission
import android.app.Activity
import android.content.pm.PackageManager
import android.content.{ActivityNotFoundException, ComponentName, Context, Intent}
import android.media.AudioManager
import android.net.{ParseException, Uri}
import android.os.{Bundle, IBinder}
import android.provider.{MediaStore, OpenableColumns}
import android.support.design.widget.{FloatingActionButton, Snackbar}
import android.support.v4.app.{ActivityCompat, ActivityOptionsCompat}
import android.support.v4.content.ContextCompat
import android.support.v7.widget.Toolbar.OnMenuItemClickListener
import android.support.v7.widget.{AppCompatEditText, AppCompatTextView}
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.ActionMode.Callback2
import android.view._
import android.view.inputmethod.InputMethodManager
import android.widget.TextView.BufferType
import android.widget.{ProgressBar, Toast}
import tk.mygod.CurrentApp
import tk.mygod.app.{CircularRevealActivity, ToolbarActivity}
import tk.mygod.content.ServicePlusConnection
import tk.mygod.os.Build
import tk.mygod.speech.tts.OnTtsSynthesisCallbackListener
import tk.mygod.util.CloseUtils._
import tk.mygod.util.{IOUtils, MetricsUtils, MimeUtils}
import tk.mygod.view.ViewPager

/**
 * @author Mygod
 */
object MainActivity {
  private final val PERMISSION_REQUEST_STORAGE = 0

  private final val OPEN_TEXT = 0
  private final val SAVE_TEXT = 1
  private final val SAVE_SYNTHESIS = 2
  private final val OPEN_EARCON = 3
}

final class MainActivity extends ToolbarActivity with TypedFindView
  with OnTtsSynthesisCallbackListener with OnMenuItemClickListener {
  import MainActivity._

  private var progressBar: ProgressBar = _
  var pager: ViewPager = _
  var inputText: AppCompatEditText = _
  var textView: AppCompatTextView = _
  private var menu: Menu = _
  var styleItem: MenuItem = _
  private var fab: FloatingActionButton = _
  private var selectionStart: Int = _
  private var selectionEnd: Int = _
  private lazy val inputMethodManager = systemService[InputMethodManager]
  private lazy val highlightSpan =
    new BackgroundColorSpan(ContextCompat.getColor(this, R.color.material_purple_300_highlight))

  val connection: ServicePlusConnection[SynthesisService] = new ServicePlusConnection[SynthesisService] {
    override def onServiceConnected(name: ComponentName, binder: IBinder) {
      super.onServiceConnected(name, binder)
      if (service.get.status == SynthesisService.IDLE) onTtsSynthesisFinished else {
        inputText.setText(service.get.rawText)
        textView.setText(service.get.rawText)
        onTtsSynthesisStarting(service.get.currentText.length)
        if (service.get.prepared >= 0) onTtsSynthesisPrepared(service.get.prepared)
        if (service.get.currentStart >= 0) onTtsSynthesisCallback(service.get.currentStart, service.get.currentEnd)
      }
      service.get.listener = MainActivity.this
    }
  }
  private var pendingUri: Uri = _
  def service = connection.service.orNull

  protected override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    configureToolbar()
    setVolumeControlStream(AudioManager.STREAM_MUSIC)
    bindService(this.intent[SynthesisService], connection, Context.BIND_AUTO_CREATE)
    toolbar.inflateMenu(R.menu.main_fragment_actions)
    menu = toolbar.getMenu
    styleItem = menu.findItem(R.id.action_style)
    toolbar.setOnMenuItemClickListener(this)
    progressBar = findView(TR.progressBar)
    fab = findView(TR.fab)
    fab.setOnClickListener(_ => if (service.status == SynthesisService.IDLE) {
      try service.speak(getText, getStartOffset) catch {
        case e: Exception =>
          e.printStackTrace
          makeToast(getString(R.string.synthesis_error, e.getLocalizedMessage)).show
          service.stop
      }
    } else service.stop)
    fab.setOnLongClickListener(_ => {
      positionToast(Toast.makeText(this, if (service.status == SynthesisService.IDLE)
        R.string.action_speak else R.string.action_stop, Toast.LENGTH_SHORT), fab, 0,
        MetricsUtils.dp2px(this, -8), true).show
      true
    })
    pager = findView(TR.pager)
    inputText = findView(TR.input_text)
    textView = findView(TR.text_view)
    if (Build.version >= 23) {
      val callback2 = new Callback2 {
        def onCreateActionMode(mode: ActionMode, menu: Menu) = {
          if (enableSsmlDroid) menu.add(0, R.id.action_style, Menu.CATEGORY_SECONDARY, R.string.action_style)
          true
        }
        def onDestroyActionMode(mode: ActionMode) = ()
        def onActionItemClicked(mode: ActionMode, item: MenuItem) = onMenuItemClick(item)
        def onPrepareActionMode(mode: ActionMode, menu: Menu) = false
      }
      inputText.setCustomInsertionActionModeCallback(callback2)
      inputText.setCustomSelectionActionModeCallback(callback2)
    }
    val intent = getIntent
    if (intent != null) {
      handleIntent(intent)
      setIntent(null)
    }
  }

  protected override def onResume() {
    super.onResume()
    styleItem.setVisible(Build.version < 23 && enableSsmlDroid)
  }

  protected override def onStart() {
    super.onStart()
    connection.service match {
      case Some(service) => service.inBackground(false)
      case _ =>
    }
  }

  protected override def onStop() {
    super.onStop()
    connection.service match {
      case Some(service) => service.inBackground(true)
      case _ =>
    }
  }

  protected override def onDestroy() {
    super.onDestroy()
    val s = service
    if (s != null) s.listener = null
    unbindService(connection)
  }

  private def canReadExtStorage =
    ContextCompat.checkSelfPermission(this, permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

  private def processUri(uri: Uri) = if (canReadExtStorage) autoClose(getContentResolver.openInputStream(uri)) {
    input =>
      inputText.setText(IOUtils.readAllText(input))
      displayName = uri.getLastPathSegment
    } else makeToast(R.string.read_file_storage_denied).show

  override def onNewIntent(data: Intent) {
    super.onNewIntent(data)
    handleIntent(intent)
  }

  def handleIntent(data: Intent) {
    if (data != null) try {
      val uri = data.getData
      if (uri == null) return
      if ((connection.service match {
        case Some(service) => service.status
        case _ => SynthesisService.IDLE
      }) != SynthesisService.IDLE) {
        makeSnackbar(R.string.error_synthesis_in_progress).show
        return
      }
      if ("file".equals(uri.getScheme)) if (canReadExtStorage) processUri(uri) else {
        pendingUri = uri
        ActivityCompat.requestPermissions(this, Array(permission.READ_EXTERNAL_STORAGE), PERMISSION_REQUEST_STORAGE)
      } else {
        autoClose(getContentResolver.openInputStream(uri))(stream =>
          inputText.setText(IOUtils.readAllText(stream)))
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
        makeSnackbar(String.format(R.string.open_error, e.getMessage))
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

  override def onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo) {
    if (v != inputText) return
    getMenuInflater.inflate(R.menu.input_text_styles, menu)
    menu.setHeaderTitle(R.string.action_style)
  }

  private def processTag(id: Int, source: CharSequence, selection: CharSequence = "",
                         value: CharSequence = null) : Boolean = {
    var tag: String = null
    var toast: String = null
    var position = 0
    var attribute = false
    id match {
      case R.id.action_tts_cardinal =>
        tag = "cardinal number=\"\""
        toast = R.string.action_tts_number_toast
      case R.id.action_tts_date =>
        try {
          val calendar = Calendar.getInstance
          val locale = Locale.getDefault
          val str = position.toString
          var i = 0
          var failed = true
          while (failed && i < 4) try {
            val instance = DateFormat.getDateInstance(i, locale)
            i += 1
            calendar.setTime(instance.parse(str))
            failed = false
          } catch {
            case ignore: ParseException =>
          }
          if (failed) throw new Exception
          tag = String.format("date year=\"%s\" month=\"%s\" day=\"%s\" weekday=\"%s\"",
            calendar.get(Calendar.YEAR): Integer, calendar.get(Calendar.MONTH): Integer,
            calendar.get(Calendar.DAY_OF_MONTH): Integer, calendar.get(Calendar.DAY_OF_WEEK): Integer)
        } catch {
          case ignore: Exception =>
            tag = "date year=\"\" month=\"\" day=\"\" weekday=\"\""
            toast = R.string.action_tts_date_toast
        }
      case R.id.action_tts_decimal =>
        try {
          val formatter = NumberFormat.getInstance(Locale.US)
          formatter.setMinimumFractionDigits(0)
          formatter.setMaximumFractionDigits(15)
          formatter.setGroupingUsed(false)
          val str = formatter.format(position.toString.toDouble)
          val i = str.indexOf('.')
          tag = String.format("decimal integer_part=\"%s\" fractional_part=\"%s\"",
            if (i < 0) str else str.substring(0, i), if (i < 0) "" else str.substring(i + 1))
        } catch {
          case ignore: NumberFormatException =>
            tag = "decimal integer_part=\"\" fractional_part=\"\""
            toast = R.string.action_tts_decimal_toast
        }
      case R.id.action_tts_digits =>
        tag = "digits digits=\"\""
        toast = if (selection.length == 0) R.string.action_tts_digits_toast_empty else R.string.action_tts_digits_toast
      case R.id.action_tts_electronic =>
        try {
          val uri = new URI(position.toString)
          val tagBuilder = new StringBuilder("electronic")
          var temp = uri.getScheme
          if (temp != null) tagBuilder.append(String.format(" protocol=\"%s\"", temp))
          temp = uri.getRawUserInfo
          if (temp != null) {
            val i = temp.indexOf(':')
            if (i < 0) tagBuilder.append(String.format(" username=\"%s\"", temp))
            else tagBuilder.append(String.format(" username=\"%s\" password=\"%s\"",
              temp.indexOf(0, i): Integer, temp.indexOf(i + 1): Integer))
          }
          temp = uri.getHost
          if (temp != null) tagBuilder.append(String.format(" domain=\"%s\"", temp))
          val port: Int = uri.getPort
          if (port >= 0) tagBuilder.append(String.format(" port=\"%s\"", port: Integer))
          temp = uri.getRawPath
          if (temp != null) tagBuilder.append(String.format(" path=\"%s\"", temp))
          temp = uri.getRawQuery
          if (temp != null) tagBuilder.append(String.format(" query_string=\"%s\"", temp))
          temp = uri.getRawFragment
          if (temp != null) tagBuilder.append(String.format(" fragment_id=\"%s\"", temp))
          tag = tagBuilder.toString
        } catch {
          case ignore: URISyntaxException =>
            tag = "electronic protocol=\"\" username=\"\" password=\"\" domain=\"\" port=\"\" path=\"\" " +
              "query_string=\"\" fragment_id=\"\""
            toast = R.string.action_tts_electronic_toast
        }
      case R.id.action_tts_fraction =>
        tag = "fraction numerator=\"\" denominator=\"\" integer_part=\"\""
        toast = R.string.action_tts_fraction_toast
      case R.id.action_tts_measure =>
        tag = "measure number=\"\" integer_part=\"\" fractional_part=\"\" numerator=\"\" denominator=\"\" unit=\"\""
        toast = R.string.action_tts_measure_toast
      case R.id.action_tts_money =>
        tag = "money integer_part=\"\" fractional_part=\"\" currency=\"\" quantity=\"\""
        toast = R.string.action_tts_money_toast
      case R.id.action_tts_ordinal =>
        tag = "ordinal number=\"\""
        toast = R.string.action_tts_number_toast
      case R.id.action_tts_telephone =>
        tag = "telephone number_parts=\"\" country_code=\"\" extension=\"\""
        toast = R.string.action_tts_telephone_toast
      case R.id.action_tts_text =>
        tag = "text text=\"\""
        toast = if (selection.length == 0) R.string.action_tts_text_toast_empty else R.string.action_tts_text_toast
      case R.id.action_tts_time =>
        try {
          val calendar = Calendar.getInstance
          val locale = Locale.getDefault
          val str = position.toString
          var i = 0
          var failed = true
          while (failed && i < 4) try {
            val instance = DateFormat.getTimeInstance(i, locale)
            i += 1
            calendar.setTime(instance.parse(str))
            failed = false
          } catch {
            case ignore: ParseException =>
          }
          if (failed) throw new Exception
          tag = String.format("time hours=\"%s\" minutes=\"%s\"", calendar.get(Calendar.HOUR_OF_DAY): Integer,
            calendar.get(Calendar.MINUTE): Integer)
        } catch {
          case ignore: Exception =>
            tag = "time hours=\"\" minutes=\"\""
            toast = R.string.action_tts_time_toast
        }
      case R.id.action_tts_verbatim =>
        tag = "verbatim verbatim=\"\""
        toast = R.string.action_tts_verbatim_toast
      case R.id.action_tts_generic_attributes_gender =>
        tag = String.format(" gender=\"%s\"", value)
        attribute = true
      case R.id.action_tts_generic_attributes_animacy =>
        tag = String.format(" animacy=\"%s\"", value)
        attribute = true
      case R.id.action_tts_generic_attributes_multiplicity =>
        tag = String.format(" multiplicity=\"%s\"", value)
        attribute = true
      case R.id.action_tts_generic_attributes_case =>
        tag = String.format(" case=\"%s\"", value)
        attribute = true
      case R.id.action_tts_earcon =>
        tag = "earcon"
      case _ =>
        return true
    }
    if (attribute) position = tag.length else {
      position = tag.indexOf("\"\"") + 2
      if (selection.length == 0) {
        tag = String.format("<%s />", tag)
        if (position < 2) position = tag.length
      } else {
        if (position < 2) position = tag.length + 2
        val i = tag.indexOf(' ')
        tag = String.format("<%s>%s</%s>", tag, selection, if (i < 0) tag else tag.substring(0, i))
      }
    }
    inputText.setTextKeepState(source.subSequence(0, selectionStart) + tag +
      source.subSequence(selectionEnd, source.length))
    inputText.setSelection(selectionStart + position)
    if (toast != null) makeToast(toast).show
    false
  }

  override def onContextItemSelected(item: MenuItem): Boolean = {
    val source = inputText.getText
    val selection = source.subSequence(selectionStart, selectionEnd)
    val id = item.getItemId
    if (id == R.id.action_tts_earcon && selection.length == 0) startActivityForResult(
      new Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION).setType("audio/*"), OPEN_EARCON)
    else if (processTag(id | item.getGroupId, source, selection, item.getTitleCondensed))
      return super.onContextItemSelected(item)
    true
  }

  private def formatDefaultText(pattern: String) = {
    val calendar = Calendar.getInstance
    val buildTime = CurrentApp.getBuildTime(this)
    calendar.setTime(buildTime)
    String.format(pattern, CurrentApp.getVersionName(this),
      DateFormat.getDateInstance(DateFormat.FULL).format(buildTime),
      DateFormat.getTimeInstance(DateFormat.FULL).format(buildTime), calendar.get(Calendar.YEAR): Integer,
      calendar.get(Calendar.MONTH): Integer, calendar.get(Calendar.DAY_OF_MONTH): Integer,
      calendar.get(Calendar.DAY_OF_WEEK): Integer, calendar.get(Calendar.HOUR_OF_DAY): Integer,
      calendar.get(Calendar.MINUTE): Integer)
  }
  def onMenuItemClick(item: MenuItem) = {
    val ssml = enableSsmlDroid
    val mime = if (ssml) "application/ssml+xml" else "text/plain"
    item.getItemId match {
      case R.id.action_style =>
        selectionStart = inputText.getSelectionStart
        selectionEnd = inputText.getSelectionEnd
        registerForContextMenu(inputText)
        openContextMenu(inputText)
        unregisterForContextMenu(inputText)
        true
      case R.id.action_synthesize_to_file =>
        val mime = service.engines.selectedEngine.getMimeType
        runOnUiThread(() => showSave(mime, getSaveFileName + '.' + MimeUtils.getExtension(mime), SAVE_SYNTHESIS))
        true
      case R.id.action_open =>
        try startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).setType(mime), OPEN_TEXT)
        catch {
          case e: ActivityNotFoundException => makeToast(R.string.open_error_no_browser).show
        }
        true
      case R.id.action_save =>
        val extension = if (ssml) ".ssml" else ".txt"
        var fileName = getSaveFileName
        if (!fileName.toLowerCase.endsWith(extension)) fileName += extension
        showSave(mime, fileName, SAVE_TEXT)
        true
      case R.id.action_populate_sample =>
        var rawText: String = null
        if (enableSsmlDroid)
          try rawText = formatDefaultText(IOUtils.readAllText(getResources.openRawResource(R.raw.input_text_default)))
          catch {
            case e: IOException => e.printStackTrace
          }
        if (rawText == null) rawText = formatDefaultText(R.string.input_text_default)
        inputText.setText(rawText)
        true
      case R.id.action_settings =>
        startActivity(CircularRevealActivity.putLocation(intent[SettingsActivity], getLocationOnScreen),
          ActivityOptionsCompat.makeSceneTransitionAnimation(this).toBundle)
        true
      case _ => false
    }
  }

  private def getStartOffset = {
    val start = pref.getString("text.start", "beginning")
    val raw = if ("selection_start" == start) inputText.getSelectionStart
    else if ("selection_end" == start) inputText.getSelectionEnd else 0
    if (service.mappings == null) raw else service.mappings.getTargetOffset(raw)
  }

  private def getText = {
    val text = inputText.getText.toString
    val temp = text.replaceAll("\r", "")
    if (text == temp) text else {
      inputText.setText(temp)
      inputText.getText.toString  // get again to keep in sync
    }
  }

  def save(uri: Uri, requestCode: Int): Unit = requestCode match {
    case SAVE_TEXT =>
      var output: OutputStream = null
      try {
        output = getContentResolver.openOutputStream(uri)
        output.write(inputText.getText.toString.getBytes)
      } catch {
        case e: IOException =>
          e.printStackTrace
          makeToast(getString(R.string.save_error, e.getMessage)).show
      } finally if (output != null) try output.close catch {
        case e: IOException => e.printStackTrace
      }
    case SAVE_SYNTHESIS =>
      try service.synthesizeToUri(getText, getStartOffset, uri) catch {
        case e: Exception =>
          e.printStackTrace
          makeToast(getString(R.string.synthesis_error, e.getMessage)).show
          service.stop
      }
  }
  override def onActivityResult(requestCode: Int, resultCode: Int, data: Intent) = requestCode match {
    case OPEN_TEXT => if (resultCode == Activity.RESULT_OK) handleIntent(data)
    case SAVE_TEXT | SAVE_SYNTHESIS => if (resultCode == Activity.RESULT_OK) save(data.getData, requestCode)
    case OPEN_EARCON => if (resultCode == Activity.RESULT_OK) {
      val uri = data.getData
      try getContentResolver.takePersistableUriPermission(uri,
        data.getFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) catch {
        case e: Exception => e.printStackTrace
      }
      processTag(R.id.action_tts_earcon, inputText.getText, uri.toString)
    } else processTag(R.id.action_tts_earcon, inputText.getText)
    case _ => super.onActivityResult(requestCode, resultCode, data)
  }

  def onTtsSynthesisStarting(length: Int) = runOnUiThread(() => {
    fab.setImageDrawable(R.drawable.ic_av_mic)
    menu.setGroupEnabled(R.id.disabled_when_synthesizing, false)
    textView.setText(inputText.getText, BufferType.EDITABLE)
    pager.setCurrentItem(1, false)
    inputMethodManager.hideSoftInputFromWindow(inputText.getWindowToken, InputMethodManager.HIDE_NOT_ALWAYS)
    progressBar.setIndeterminate(true)
    progressBar.setVisibility(View.VISIBLE)
    progressBar.setMax(length)
  })
  def onTtsSynthesisPrepared(end: Int) = runOnUiThread(() => {
    if (progressBar.isIndeterminate) {
      progressBar.setIndeterminate(false)
      progressBar.setProgress(0)
    }
    progressBar.setSecondaryProgress(end)
  })
  def onTtsSynthesisCallback(s: Int, e: Int) = {
    var start = s
    var end = e
    if (service.mappings != null) {
      start = service.mappings.getSourceOffset(start, false)
      end = service.mappings.getSourceOffset(end, true)
    }
    if (end < start) end = start
    runOnUiThread(() => {
      if (progressBar.isIndeterminate) {
        progressBar.setIndeterminate(false)
        progressBar.setSecondaryProgress(0)
      }
      progressBar.setProgress(s)
      inputText.setSelection(start, end)
      if (start != end) textView.getEditableText.setSpan(highlightSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      else textView.getEditableText.removeSpan(highlightSpan)
      textView.bringPointIntoView(end)
    })
  }
  def onTtsSynthesisError(start: Int, end: Int) = ()
  def onTtsSynthesisFinished = runOnUiThread(() => {
    fab.setImageDrawable(R.drawable.ic_av_mic_none)
    menu.setGroupEnabled(R.id.disabled_when_synthesizing, true)
    pager.setCurrentItem(0, false)
    progressBar.setVisibility(View.INVISIBLE)
  })

  override def makeSnackbar(text: CharSequence, duration: Int = Snackbar.LENGTH_LONG, view: View = fab) =
    super.makeSnackbar(text, duration, view)

  def showSave(mimeType: String, fileName: String, requestCode: Int) =
    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
      .putExtra(Intent.EXTRA_TITLE, fileName).setType(mimeType), requestCode)
}
