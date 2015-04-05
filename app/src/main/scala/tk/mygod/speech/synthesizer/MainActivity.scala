package tk.mygod.speech.synthesizer

import java.io.{FileOutputStream, IOException, InputStream, OutputStream}
import java.net.{URI, URISyntaxException}
import java.text.{SimpleDateFormat, DateFormat, NumberFormat}
import java.util.{Calendar, Date, Locale}

import android.app.{Activity, NotificationManager}
import android.content.{Context, Intent}
import android.net.ParseException
import android.os.{Build, Bundle, ParcelFileDescriptor}
import android.provider.{MediaStore, OpenableColumns}
import android.support.v4.app.NotificationCompat
import android.support.v7.widget.Toolbar.OnMenuItemClickListener
import android.text.InputFilter
import android.view.inputmethod.InputMethodManager
import android.view.{ContextMenu, Menu, MenuItem, View}
import android.webkit.MimeTypeMap
import android.widget.{EditText, ProgressBar}
import com.melnykov.fab.FloatingActionButton
import tk.mygod.CurrentApp
import tk.mygod.app.{SaveFileActivity, ToolbarActivity}
import tk.mygod.speech.tts.OnTtsSynthesisCallbackListener
import tk.mygod.text.{SsmlDroid, TextMappings}
import tk.mygod.util.MethodWrappers._
import tk.mygod.util.IOUtils
import tk.mygod.widget.ObservableScrollView

/**
 * @author Mygod
 */
object MainActivity {
  val OPEN_TEXT = 0
  val SAVE_TEXT = 1
  val SAVE_SYNTHESIS = 2
  val OPEN_EARCON = 3
  val IDLE = 0
  val SPEAKING = 1
  val SYNTHESIZING = 2
  val noFilters = new Array[InputFilter](0)
  val readonlyFilters = Array[InputFilter](inputFilter((src, start, end, dest, dstart, dend) =>
    dest.subSequence(dstart, dend)))
}

final class MainActivity extends ToolbarActivity with OnTtsSynthesisCallbackListener
  with OnSelectedEngineChangingListener with OnMenuItemClickListener {
  private var progressBar: ProgressBar = _
  private var inputText: EditText = _
  private var menu: Menu = _
  private var styleItem: MenuItem = _
  private var earconItem: MenuItem = _
  private var fab: FloatingActionButton = _
  private var status: Int = _
  private var selectionStart: Int = _
  private var selectionEnd: Int = _
  private var inBackground: Boolean = _
  private var mappings: TextMappings = _
  private var descriptor: ParcelFileDescriptor = _
  private var builder: NotificationCompat.Builder = _
  private var lastText: String = _
  private var displayName: String = _
  private lazy val notificationManager =
    getSystemService(Context.NOTIFICATION_SERVICE).asInstanceOf[NotificationManager]
  private lazy val inputMethodManager =
    getSystemService(Context.INPUT_METHOD_SERVICE).asInstanceOf[InputMethodManager]

  private def showNotification(text: CharSequence) {
    if (status != MainActivity.SPEAKING) lastText = null
    else if (text != null) lastText = text.toString.replaceAll("\\s+", " ")
    if (inBackground) notificationManager.notify(0, new NotificationCompat.BigTextStyle(builder
      .setWhen(System.currentTimeMillis).setContentText(lastText)
      .setTicker(if (TtsEngineManager.pref.getBoolean("appearance.ticker", false)) lastText else null)
      .setPriority(TtsEngineManager.pref.getString("appearance.notificationType", "0").toInt)
      .setVibrate(new Array[Long](0))).bigText(lastText).build)
  }

  private def cancelNotification {
    inBackground = false
    notificationManager.cancel(0)
  }

  private def formatDefaultText(pattern: String, buildTime: Date) = {
    val calendar = Calendar.getInstance
    calendar.setTime(buildTime)
    String.format(pattern, CurrentApp.getVersionName(this),
                  DateFormat.getDateInstance(DateFormat.FULL).format(buildTime),
                  DateFormat.getTimeInstance(DateFormat.FULL).format(buildTime), calendar.get(Calendar.YEAR): Integer,
                  calendar.get(Calendar.MONTH): Integer, calendar.get(Calendar.DAY_OF_MONTH): Integer,
                  calendar.get(Calendar.DAY_OF_WEEK): Integer, calendar.get(Calendar.HOUR_OF_DAY): Integer,
                  calendar.get(Calendar.MINUTE): Integer)
  }

  protected override def onCreate(icicle: Bundle) {
    super.onCreate(icicle)
    setContentView(R.layout.activity_main)
    configureToolbar()
    toolbar.inflateMenu(R.menu.main_activity_actions)
    menu = toolbar.getMenu
    styleItem = menu.findItem(R.id.action_style)
    toolbar.setOnMenuItemClickListener(this)
    progressBar = findViewById(R.id.progressBar).asInstanceOf[ProgressBar]
    fab = findViewById(R.id.fab).asInstanceOf[FloatingActionButton]
    findViewById(R.id.scroller).asInstanceOf[ObservableScrollView]
      .setScrollViewListener((scrollView: ObservableScrollView, x: Int, y: Int, oldx: Int, oldy: Int) => {
          if (y > oldy) fab.hide else if (y < oldy) fab.show
      })
    TtsEngineManager.init(this, this)
    val buildTime = CurrentApp.getBuildTime(this)
    inputText = findViewById(R.id.input_text).asInstanceOf[EditText]
    var failed = true
    if (TtsEngineManager.getEnableSsmlDroid) try {
      inputText.setText(formatDefaultText(IOUtils.readAllText(getResources.openRawResource(R.raw.input_text_default)),
                                          buildTime))
      failed = false
    } catch {
      case e: IOException => e.printStackTrace
    }
    if (failed) inputText.setText(formatDefaultText(R.string.input_text_default, buildTime))
    builder = new NotificationCompat.Builder(this).setContentTitle(R.string.notification_title).setAutoCancel(true)
      .setSmallIcon(R.drawable.ic_communication_message).setColor(getResources.getColor(R.color.material_purple_500))
      .setContentIntent(pendingIntent[MainActivity]).setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setDeleteIntent(pendingIntentBroadcast("tk.mygod.speech.synthesizer.action.STOP"))
    val intent = getIntent
    if (intent.getData != null) onNewIntent(intent)
  }

  protected override def onNewIntent(data: Intent) {
    if (data == null) return
    var input: InputStream = null
    try {
      val uri = data.getData
      if (uri == null) return
      if (status != MainActivity.IDLE) {
        showToast(R.string.error_synthesis_in_progress)
        return
      }
      input = getContentResolver.openInputStream(uri)
      inputText.setText(IOUtils.readAllText(input))
      if ("file".equalsIgnoreCase(uri.getScheme)) displayName = uri.getLastPathSegment
      else {
        val cursor = getContentResolver.query(uri, Array(OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.TITLE),
                                              null, null, null)
        if (cursor != null) {
          if (cursor.moveToFirst) {
            var index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            displayName = cursor.getString(index)
            if (index < 0 || displayName == null) {
              index = cursor.getColumnIndex(MediaStore.MediaColumns.TITLE)
              displayName = cursor.getString(index)
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

  override def onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo) {
    if (v != inputText) return
    getMenuInflater.inflate(R.menu.input_text_styles, menu)
    menu.setHeaderTitle(R.string.action_style)
    earconItem = menu.findItem(R.id.action_tts_earcon)
  }

  private def processTag(item: MenuItem, source: CharSequence, selection: CharSequence): Boolean = {
    var tag: String = null
    var toast: String = null
    var selection: Int = 0
    var attribute = false
    item.getGroupId | item.getItemId match {
      case R.id.action_tts_cardinal =>
        tag = "cardinal number=\"\""
        toast = R.string.action_tts_number_toast
      case R.id.action_tts_date =>
        try {
          val calendar = Calendar.getInstance
          val locale = Locale.getDefault
          val str = selection.toString
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
          val str = formatter.format(selection.toString.toDouble)
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
          val uri = new URI(selection.toString)
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
          val str = selection.toString
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
        tag = String.format(" gender=\"%s\"", item.getTitleCondensed)
        attribute = true
      case R.id.action_tts_generic_attributes_animacy =>
        tag = String.format(" animacy=\"%s\"", item.getTitleCondensed)
        attribute = true
      case R.id.action_tts_generic_attributes_multiplicity =>
        tag = String.format(" multiplicity=\"%s\"", item.getTitleCondensed)
        attribute = true
      case R.id.action_tts_generic_attributes_case =>
        tag = String.format(" case=\"%s\"", item.getTitleCondensed)
        attribute = true
      case R.id.action_tts_earcon =>
        tag = "earcon"
      case _ =>
        return true
    }
    if (attribute) selection = tag.length
    else {
      selection = tag.indexOf("\"\"") + 2
      if (selection.length == 0) {
        tag = String.format("<%s />", tag)
        if (selection < 2) selection = tag.length
      }
      else {
        if (selection < 2) selection = tag.length + 2
        val i = tag.indexOf(' ')
        tag = String.format("<%s>%s</%s>", tag, selection: Integer, if (i < 0) tag else tag.substring(0, i))
      }
    }
    inputText.setTextKeepState(source
      .subSequence(0, selectionStart) + tag + source.subSequence(selectionEnd, source.length))
    inputText.setSelection(selectionStart + selection)
    if (toast != null) showToast(toast)
    false
  }

  override def onContextItemSelected(item: MenuItem): Boolean = {
    val source = inputText.getText
    val selection = source.subSequence(selectionStart, selectionEnd)
    if (item.getItemId == R.id.action_tts_earcon && selection.length == 0) {
      val intent = new Intent(Intent.ACTION_GET_CONTENT)
      intent.addCategory(Intent.CATEGORY_OPENABLE)
      intent.setType("audio/*")
      startActivityForResult(intent, MainActivity.OPEN_EARCON)
    } else if (processTag(item, source, selection)) return super.onContextItemSelected(item)
    true
  }

  protected override def onStop {
    if (status != MainActivity.IDLE) {
      inBackground = true
      showNotification(null)
    }
    super.onStop
  }

  protected override def onStart {
    super.onStart
    cancelNotification
    styleItem.setVisible(TtsEngineManager.getEnableSsmlDroid)
  }

  def onMenuItemClick(item: MenuItem) = {
    item.getItemId match {
      case R.id.action_style =>
        selectionStart = inputText.getSelectionStart
        selectionEnd = inputText.getSelectionEnd
        registerForContextMenu(inputText)
        openContextMenu(inputText)
        unregisterForContextMenu(inputText)
        true
      case R.id.action_synthesize_to_file =>
        val fileName = getSaveFileName + '.' +
          MimeTypeMap.getSingleton.getExtensionFromMimeType(TtsEngineManager.engines.selectedEngine.getMimeType)
        var intent: Intent = null
        if (TtsEngineManager.getOldTimeySaveUI) {
          intent = intentActivity[SaveFileActivity]
          val dir: String = TtsEngineManager.getLastSaveDir
          if (dir != null) intent.putExtra(SaveFileActivity.EXTRA_CURRENT_DIRECTORY, dir)
        } else intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent.putExtra(Intent.EXTRA_TITLE, fileName)
          .setType(TtsEngineManager.engines.selectedEngine.getMimeType), MainActivity.SAVE_SYNTHESIS)
        true
      case R.id.action_open =>
        startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
          .setType("text/plain"), MainActivity.OPEN_TEXT)
        true
      case R.id.action_save =>
        var fileName = getSaveFileName
        if (!fileName.toLowerCase.endsWith(".txt")) fileName += ".txt"
        var intent: Intent = null
        if (TtsEngineManager.getOldTimeySaveUI) {
          intent = intentActivity[SaveFileActivity]
          val dir = TtsEngineManager.getLastSaveDir
          if (dir != null) intent.putExtra(SaveFileActivity.EXTRA_CURRENT_DIRECTORY, dir)
        } else intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent.putExtra(Intent.EXTRA_TITLE, fileName).setType("text/plain"),
                               MainActivity.SAVE_TEXT)
        true
      case R.id.action_settings =>
        startActivity(intentActivity[SettingsActivity])
        true
      case _ => false
    }
  }

  private def startSynthesis {
    TtsEngineManager.engines.selectedEngine.setPitch(TtsEngineManager.pref.getString("tweaks.pitch", "1").toFloat)
    TtsEngineManager.engines.selectedEngine
      .setSpeechRate(TtsEngineManager.pref.getString("tweaks.speechRate", "1").toFloat)
    TtsEngineManager.engines.selectedEngine.setPan(TtsEngineManager.pref.getString("tweaks.pan", "0").toFloat)
    fab.setImageDrawable(getResources.getDrawable(R.drawable.ic_av_mic))
    menu.setGroupEnabled(R.id.disabled_when_synthesizing, false)
    inputText.setFilters(MainActivity.readonlyFilters)
    inputMethodManager.hideSoftInputFromWindow(inputText.getWindowToken, InputMethodManager.HIDE_NOT_ALWAYS)
    builder.setProgress(0, 0, true)
    progressBar.setIndeterminate(true)
    progressBar.setVisibility(View.VISIBLE)
    progressBar.setMax(inputText.getText.length)
  }

  def stopSynthesis {
    TtsEngineManager.engines.selectedEngine.stop
    fab.setImageDrawable(getResources.getDrawable(R.drawable.ic_av_mic_none))
    menu.setGroupEnabled(R.id.disabled_when_synthesizing, true)
    inputText.setFilters(MainActivity.noFilters)
    progressBar.setVisibility(View.INVISIBLE)
    if (descriptor != null) descriptor = null
    status = MainActivity.IDLE
    fab.show
    cancelNotification
  }

  def onSelectedEngineChanging {
    stopSynthesis
  }

  private def getStartOffset = {
    val start = TtsEngineManager.pref.getString("text.start", "beginning")
    if ("selection_start" == start) inputText.getSelectionStart
    else if ("selection_end" == start) inputText.getSelectionEnd else 0
  }

  private def getSaveFileName = if (displayName == null)
    new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date) else displayName

  private def getText = {
    var text = inputText.getText.toString
    val temp = text.replaceAll("\r", "")
    if (text != temp) {
      inputText.setText(temp)
      text = inputText.getText.toString // get again to keep in sync
    }
    if (TtsEngineManager.getEnableSsmlDroid) {
      val parser: SsmlDroid.Parser = SsmlDroid.fromSsml(text, TtsEngineManager.getIgnoreSingleLineBreak, null)
      mappings = parser.Mappings
      parser.Result
    } else {
      mappings = null
      if (TtsEngineManager.getIgnoreSingleLineBreak) text.replaceAll("(?<!\\n)(\\n)(?!\\n)", " ") else text
    }
  }

  def synthesize(view: View) = if (status == MainActivity.IDLE) {
    try {
      status = MainActivity.SPEAKING
      val text = getText
      startSynthesis
      TtsEngineManager.engines.selectedEngine.setSynthesisCallbackListener(this)
      TtsEngineManager.engines.selectedEngine.speak(text, getStartOffset)
    } catch {
      case e: Exception =>
        e.printStackTrace
        showToast(String.format(R.string.synthesis_error, e.getLocalizedMessage))
        stopSynthesis
    }
  } else stopSynthesis

  protected override def onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
    requestCode match {
      case MainActivity.OPEN_TEXT => if (resultCode == Activity.RESULT_OK) onNewIntent(data)
      case MainActivity.SAVE_TEXT =>
        var output: OutputStream = null
        if (resultCode == Activity.RESULT_OK) try {
          output = getContentResolver.openOutputStream(data.getData)
          output.write(inputText.getText.toString.getBytes)
        } catch {
          case e: IOException =>
            e.printStackTrace
            showToast(String.format(R.string.save_error, e.getMessage))
        } finally if (output != null) try output.close catch {
          case e: IOException => e.printStackTrace
        }
      case MainActivity.SAVE_SYNTHESIS => if (resultCode == Activity.RESULT_OK) try {
        status = MainActivity.SYNTHESIZING
        val text = getText
        startSynthesis
        TtsEngineManager.engines.selectedEngine.setSynthesisCallbackListener(this)
        descriptor = getContentResolver.openFileDescriptor(data.getData, "w")
        TtsEngineManager.engines.selectedEngine.synthesizeToStream(text, getStartOffset,
          new FileOutputStream(descriptor.getFileDescriptor), getCacheDir)
      } catch {
        case e: Exception =>
          e.printStackTrace
          showToast(String.format(R.string.synthesis_error, e.getMessage))
          stopSynthesis
      }
      case MainActivity.OPEN_EARCON => if (resultCode == Activity.RESULT_OK) {
        val uri = data.getData
        if (Build.VERSION.SDK_INT >= 19)
          try getContentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) catch {
            case ignore: Exception =>
          }
        processTag(earconItem, inputText.getText, uri.toString)
      }
      else processTag(earconItem, inputText.getText, "")
      case _ => super.onActivityResult(requestCode, resultCode, data)
    }
  }

  def onTtsSynthesisPrepared(e: Int) {
    val end = if (mappings == null) e else mappings.getSourceOffset(e, true)
    runOnUiThread {
      if (progressBar.isIndeterminate) {
        progressBar.setIndeterminate(false)
        progressBar.setProgress(0)
      }
      progressBar.setSecondaryProgress(end)
    }
  }

  def onTtsSynthesisCallback(s: Int, e: Int) {
    var start = s
    var end = e
    if (mappings != null) {
      start = mappings.getSourceOffset(start, false)
      end = mappings.getSourceOffset(end, true)
    }
    if (end < start) end = start
    runOnUiThread {
      if (status != MainActivity.IDLE) {
        if (progressBar.isIndeterminate) {
          progressBar.setIndeterminate(false)
          progressBar.setSecondaryProgress(0)
        }
        progressBar.setProgress(start)
      }
      builder.setProgress(progressBar.getMax, start, false)
      inputText.setSelection(start, end)
      inputText.moveCursorToVisibleOffset
      showNotification(inputText.getText.subSequence(start, end))
    }
  }

  def onTtsSynthesisError(s: Int, e: Int) {
    var start = s
    var end = e
    if (mappings != null) {
      start = mappings.getSourceOffset(start, false)
      end = mappings.getSourceOffset(end, true)
    }
    if (end < start) end = start
    runOnUiThread(if (start < end)
      showToast(String.format(R.string.synthesis_error, inputText.getText.toString.substring(start, end))))
  }

  def onTtsSynthesisFinished = runOnUiThread(stopSynthesis)

  override def onDestroy {
    stopSynthesis
    TtsEngineManager.destroy
    super.onDestroy
  }
}
