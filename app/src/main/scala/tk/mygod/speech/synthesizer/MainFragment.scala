package tk.mygod.speech.synthesizer

import java.io.{FileOutputStream, IOException, OutputStream}
import java.net.{URI, URISyntaxException}
import java.text.{DateFormat, NumberFormat, SimpleDateFormat}
import java.util.{Calendar, Date, Locale}

import android.app.{Activity, NotificationManager}
import android.content.{ActivityNotFoundException, Context, Intent}
import android.net.{ParseException, Uri}
import android.os.{Build, Bundle, ParcelFileDescriptor}
import android.support.design.widget.FloatingActionButton
import android.support.v4.app.NotificationCompat
import android.support.v7.widget.AppCompatEditText
import android.support.v7.widget.Toolbar.OnMenuItemClickListener
import android.text.InputFilter
import android.view._
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.ProgressBar
import tk.mygod.CurrentApp
import tk.mygod.app.ToolbarFragment
import tk.mygod.speech.tts.OnTtsSynthesisCallbackListener
import tk.mygod.text.{SsmlDroid, TextMappings}
import tk.mygod.util.IOUtils
import tk.mygod.util.MethodWrappers._

/**
 * @author Mygod
 */
object MainFragment {
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

final class MainFragment extends ToolbarFragment
  with OnTtsSynthesisCallbackListener with OnSelectedEngineChangingListener with OnMenuItemClickListener {
  private var progressBar: ProgressBar = _
  var inputText: AppCompatEditText = _
  private var menu: Menu = _
  private var styleItem: MenuItem = _
  private var earconItem: MenuItem = _
  private var fab: FloatingActionButton = _
  var status: Int = _
  private var selectionStart: Int = _
  private var selectionEnd: Int = _
  private var inBackground: Boolean = _
  private var mappings: TextMappings = _
  private var descriptor: ParcelFileDescriptor = _
  private var lastText: String = _
  var displayName: String = _
  private lazy val notificationManager =
    getActivity.getSystemService(Context.NOTIFICATION_SERVICE).asInstanceOf[NotificationManager]
  private lazy val inputMethodManager =
    getActivity.getSystemService(Context.INPUT_METHOD_SERVICE).asInstanceOf[InputMethodManager]

  private def showNotification(text: CharSequence) {
    if (status != MainFragment.SPEAKING) lastText = null
    else if (text != null) lastText = text.toString.replaceAll("\\s+", " ")
    if (inBackground) notificationManager.notify(0, new NotificationCompat.BigTextStyle(TtsEngineManager.mainActivity
      .builder.setWhen(System.currentTimeMillis).setContentText(lastText)
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
    String.format(pattern, CurrentApp.getVersionName(getActivity),
      DateFormat.getDateInstance(DateFormat.FULL).format(buildTime),
      DateFormat.getTimeInstance(DateFormat.FULL).format(buildTime), calendar.get(Calendar.YEAR): Integer,
      calendar.get(Calendar.MONTH): Integer, calendar.get(Calendar.DAY_OF_MONTH): Integer,
      calendar.get(Calendar.DAY_OF_WEEK): Integer, calendar.get(Calendar.HOUR_OF_DAY): Integer,
      calendar.get(Calendar.MINUTE): Integer)
  }

  override def isFullscreen = true

  override def onAttach(activity: Activity) {
    super.onAttach(activity)
    activity.asInstanceOf[MainActivity].mainFragment = this
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle) = {
    val result = inflater.inflate(R.layout.fragment_main, container, false)
    configureToolbar(result, R.string.app_name)
    toolbar.inflateMenu(R.menu.main_activity_actions)
    menu = toolbar.getMenu
    styleItem = menu.findItem(R.id.action_style)
    toolbar.setOnMenuItemClickListener(this)
    progressBar = result.findViewById(R.id.progressBar).asInstanceOf[ProgressBar]
    fab = result.findViewById(R.id.fab).asInstanceOf[FloatingActionButton]
    fab.setOnClickListener((v: View) => if (status == MainFragment.IDLE) {
      try {
        status = MainFragment.SPEAKING
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
    } else stopSynthesis)
    val buildTime = CurrentApp.getBuildTime(getActivity)
    inputText = result.findViewById(R.id.input_text).asInstanceOf[AppCompatEditText]
    TtsEngineManager.init(getActivity.asInstanceOf[MainActivity], this)
    var failed = true
    if (TtsEngineManager.enableSsmlDroid) try {
      inputText.setText(formatDefaultText(IOUtils.readAllText(getResources.openRawResource(R.raw.input_text_default)),
        buildTime))
      failed = false
    } catch {
      case e: IOException => e.printStackTrace
    }
    if (failed) inputText.setText(formatDefaultText(R.string.input_text_default, buildTime))
    val intent = TtsEngineManager.mainActivity.getIntent
    if (intent != null) TtsEngineManager.mainActivity.onNewIntent(intent)
    result
  }

  override def onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo) {
    if (v != inputText) return
    getActivity.getMenuInflater.inflate(R.menu.input_text_styles, menu)
    menu.setHeaderTitle(R.string.action_style)
    earconItem = menu.findItem(R.id.action_tts_earcon)
  }

  private def processTag(item: MenuItem, source: CharSequence, selection: CharSequence): Boolean = {
    var tag: String = null
    var toast: String = null
    var position = 0
    var attribute = false
    item.getGroupId | item.getItemId match {
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
      startActivityForResult(intent, MainFragment.OPEN_EARCON)
    } else if (processTag(item, source, selection)) return super.onContextItemSelected(item)
    true
  }

  override def onStop {
    if (status != MainFragment.IDLE) {
      inBackground = true
      showNotification(null)
    }
    super.onStop
  }

  override def onStart {
    super.onStart
    cancelNotification
    styleItem.setVisible(TtsEngineManager.enableSsmlDroid)
  }

  def onMenuItemClick(item: MenuItem) = {
    val ssml = TtsEngineManager.enableSsmlDroid
    val mime = if (ssml) "application/ssml+xml" else "text/plain"
    item.getItemId match {
      case R.id.action_style =>
        selectionStart = inputText.getSelectionStart
        selectionEnd = inputText.getSelectionEnd
        registerForContextMenu(inputText)
        getActivity.openContextMenu(inputText)
        unregisterForContextMenu(inputText)
        true
      case R.id.action_synthesize_to_file =>
        TtsEngineManager.mainActivity.showSave(TtsEngineManager.engines.selectedEngine.getMimeType, getSaveFileName +
          '.' + MimeTypeMap.getSingleton.getExtensionFromMimeType(TtsEngineManager.engines.selectedEngine.getMimeType),
          MainFragment.SAVE_SYNTHESIS)
        true
      case R.id.action_open =>
        try startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
          .setType(mime), MainFragment.OPEN_TEXT)
        catch {
          case e: ActivityNotFoundException => showToast(R.string.open_error_no_browser)
        }
        true
      case R.id.action_save =>
        val extension = if (ssml) ".ssml" else ".txt"
        var fileName = getSaveFileName
        if (!fileName.toLowerCase.endsWith(extension)) fileName += extension
        TtsEngineManager.mainActivity.showSave(mime, fileName, MainFragment.SAVE_TEXT)
        true
      case R.id.action_settings =>
        TtsEngineManager.mainActivity.showSettings
        true
      case _ => false
    }
  }

  private def startSynthesis {
    TtsEngineManager.engines.selectedEngine.setPitch(TtsEngineManager.pref.getString("tweaks.pitch", "1").toFloat)
    TtsEngineManager.engines.selectedEngine
      .setSpeechRate(TtsEngineManager.pref.getString("tweaks.speechRate", "1").toFloat)
    TtsEngineManager.engines.selectedEngine.setPan(TtsEngineManager.pref.getString("tweaks.pan", "0").toFloat)
    fab.setImageDrawable(R.drawable.ic_av_mic)
    menu.setGroupEnabled(R.id.disabled_when_synthesizing, false)
    inputText.setFilters(MainFragment.readonlyFilters)
    inputMethodManager.hideSoftInputFromWindow(inputText.getWindowToken, InputMethodManager.HIDE_NOT_ALWAYS)
    TtsEngineManager.mainActivity.builder.setProgress(0, 0, true)
    progressBar.setIndeterminate(true)
    progressBar.setVisibility(View.VISIBLE)
    progressBar.setMax(inputText.getText.length)
  }

  def stopSynthesis {
    TtsEngineManager.engines.selectedEngine.stop
    fab.setImageDrawable(R.drawable.ic_av_mic_none)
    menu.setGroupEnabled(R.id.disabled_when_synthesizing, true)
    inputText.setFilters(MainFragment.noFilters)
    progressBar.setVisibility(View.INVISIBLE)
    if (descriptor != null) descriptor = null
    status = MainFragment.IDLE
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
    if (TtsEngineManager.enableSsmlDroid) {
      val parser: SsmlDroid.Parser = SsmlDroid.fromSsml(text, TtsEngineManager.ignoreSingleLineBreak, null)
      mappings = parser.Mappings
      parser.Result
    } else {
      mappings = null
      if (TtsEngineManager.ignoreSingleLineBreak) text.replaceAll("(?<!\\n)(\\n)(?!\\n)", " ") else text
    }
  }

  def save(uri: Uri, requestCode: Int) {
    requestCode match {
      case MainFragment.SAVE_TEXT =>
        var output: OutputStream = null
        try {
          output = getActivity.getContentResolver.openOutputStream(uri)
          output.write(inputText.getText.toString.getBytes)
        } catch {
          case e: IOException =>
            e.printStackTrace
            showToast(String.format(R.string.save_error, e.getMessage))
        } finally if (output != null) try output.close catch {
          case e: IOException => e.printStackTrace
        }
      case MainFragment.SAVE_SYNTHESIS =>
        try {
          status = MainFragment.SYNTHESIZING
          val text = getText
          startSynthesis
          TtsEngineManager.engines.selectedEngine.setSynthesisCallbackListener(this)
          descriptor = getActivity.getContentResolver.openFileDescriptor(uri, "w")
          TtsEngineManager.engines.selectedEngine.synthesizeToStream(text, getStartOffset,
            new FileOutputStream(descriptor.getFileDescriptor), getActivity.getCacheDir)
        } catch {
          case e: Exception =>
            e.printStackTrace
            showToast(String.format(R.string.synthesis_error, e.getMessage))
            stopSynthesis
        }
    }
  }
  protected override def onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
    requestCode match {
      case MainFragment.OPEN_TEXT =>
        if (resultCode == Activity.RESULT_OK) TtsEngineManager.mainActivity.onNewIntent(data)
      case MainFragment.SAVE_TEXT | MainFragment.SAVE_SYNTHESIS =>
        if (resultCode == Activity.RESULT_OK) save(data.getData, requestCode)
      case MainFragment.OPEN_EARCON => if (resultCode == Activity.RESULT_OK) {
        val uri = data.getData
        if (Build.VERSION.SDK_INT >= 19)
          try getActivity.getContentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
          catch {
            case ignore: Exception =>
          }
        processTag(earconItem, inputText.getText, uri.toString)
      } else processTag(earconItem, inputText.getText, "")
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
      if (status != MainFragment.IDLE) {
        if (progressBar.isIndeterminate) {
          progressBar.setIndeterminate(false)
          progressBar.setSecondaryProgress(0)
        }
        progressBar.setProgress(start)
      }
      TtsEngineManager.mainActivity.builder.setProgress(progressBar.getMax, start, false)
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