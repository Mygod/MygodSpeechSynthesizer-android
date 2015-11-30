package tk.mygod.speech.synthesizer

import java.io.{IOException, OutputStream}
import java.net.{URI, URISyntaxException}
import java.text.{DateFormat, NumberFormat}
import java.util.{Calendar, Date, Locale}

import android.app.Activity
import android.content._
import android.net.{ParseException, Uri}
import android.os.Bundle
import android.support.design.widget.{FloatingActionButton, Snackbar}
import android.support.v4.content.ContextCompat
import android.support.v4.view.ViewPager
import android.support.v7.widget.Toolbar.OnMenuItemClickListener
import android.support.v7.widget.{AppCompatEditText, AppCompatTextView}
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.ActionMode.Callback2
import android.view._
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.ProgressBar
import android.widget.TextView.BufferType
import tk.mygod.CurrentApp
import tk.mygod.app.ToolbarFragment
import tk.mygod.os.Build
import tk.mygod.speech.synthesizer.MainFragment._
import tk.mygod.speech.synthesizer.TypedResource._
import tk.mygod.speech.tts.OnTtsSynthesisCallbackListener
import tk.mygod.util.IOUtils
import tk.mygod.widget.ViewPagerAdapter

/**
 * @author Mygod
 */
object MainFragment {
  val OPEN_TEXT = 0
  val SAVE_TEXT = 1
  val SAVE_SYNTHESIS = 2
  val OPEN_EARCON = 3
}

final class MainFragment extends ToolbarFragment with OnTtsSynthesisCallbackListener with OnMenuItemClickListener {
  private var mainActivity: MainActivity = _
  private var progressBar: ProgressBar = _
  var pager: ViewPager = _
  var inputText: AppCompatEditText = _
  var textView: AppCompatTextView = _
  private var menu: Menu = _
  var styleItem: MenuItem = _
  private var fab: FloatingActionButton = _
  private var selectionStart: Int = _
  private var selectionEnd: Int = _
  private lazy val inputMethodManager = mainActivity.systemService[InputMethodManager]
  private lazy val highlightSpan =
    new BackgroundColorSpan(ContextCompat.getColor(mainActivity, R.color.material_purple_300_highlight))

  private def formatDefaultText(pattern: String, buildTime: Date) = {
    val calendar = Calendar.getInstance
    calendar.setTime(buildTime)
    String.format(pattern, CurrentApp.getVersionName(mainActivity),
      DateFormat.getDateInstance(DateFormat.FULL).format(buildTime),
      DateFormat.getTimeInstance(DateFormat.FULL).format(buildTime), calendar.get(Calendar.YEAR): Integer,
      calendar.get(Calendar.MONTH): Integer, calendar.get(Calendar.DAY_OF_MONTH): Integer,
      calendar.get(Calendar.DAY_OF_WEEK): Integer, calendar.get(Calendar.HOUR_OF_DAY): Integer,
      calendar.get(Calendar.MINUTE): Integer)
  }

  override def isFullscreen = true

  //noinspection ScalaDeprecation
  override def onAttach(activity: Activity) {
    super.onAttach(activity)
    mainActivity = activity.asInstanceOf[MainActivity]
    if (App.mainFragment != null) throw new RuntimeException("MainFragment is being attached twice!")
    App.mainFragment = this
  }
  override def onDetach {
    super.onDetach
    mainActivity = null
    App.mainFragment = null
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle) = {
    val result = inflater.inflate(R.layout.fragment_main, container, false)
    configureToolbar(result, R.string.app_name)
    toolbar.inflateMenu(R.menu.main_fragment_actions)
    menu = toolbar.getMenu
    styleItem = menu.findItem(R.id.action_style)
    styleItem.setVisible(Build.version < 23 && App.enableSsmlDroid)
    toolbar.setOnMenuItemClickListener(this)
    progressBar = result.findView(TR.progressBar)
    fab = result.findView(TR.fab)
    fab.setOnClickListener(_ => SynthesisService.write(if (SynthesisService.instance.status == SynthesisService.IDLE) {
      try SynthesisService.instance.speak(getText, getStartOffset) catch {
        case e: Exception =>
          e.printStackTrace
          showToast(String.format(R.string.synthesis_error, e.getLocalizedMessage))
          SynthesisService.instance.stop
      }
    } else SynthesisService.instance.stop))
    val buildTime = CurrentApp.getBuildTime(mainActivity)
    pager = result.findView(TR.pager)
    pager.setAdapter(new ViewPagerAdapter(pager))
    inputText = result.findView(TR.input_text)
    textView = result.findView(TR.text_view)
    if (Build.version >= 23) {
      val callback2 = new Callback2 {
        def onCreateActionMode(mode: ActionMode, menu: Menu) = {
          if (App.enableSsmlDroid) menu.add(0, R.id.action_style, Menu.CATEGORY_SECONDARY, R.string.action_style)
          true
        }
        def onDestroyActionMode(mode: ActionMode) = ()
        def onActionItemClicked(mode: ActionMode, item: MenuItem) = onMenuItemClick(item)
        def onPrepareActionMode(mode: ActionMode, menu: Menu) = false
      }
      inputText.setCustomInsertionActionModeCallback(callback2)
      inputText.setCustomSelectionActionModeCallback(callback2)
    }
    var failed = true
    if (App.enableSsmlDroid) try {
      inputText.setText(formatDefaultText(IOUtils.readAllText(getResources.openRawResource(R.raw.input_text_default)),
        buildTime))
      failed = false
    } catch {
      case e: IOException => e.printStackTrace
    }
    if (failed) inputText.setText(formatDefaultText(R.string.input_text_default, buildTime))
    val intent = mainActivity.getIntent
    if (intent != null) mainActivity.onNewIntent(intent)
    result
  }

  override def onViewStateRestored(savedInstanceState: Bundle) {
    super.onViewStateRestored(savedInstanceState)
    // update the user interface while the activity is dead
    if (!SynthesisService.ready || SynthesisService.instance.status == SynthesisService.IDLE) onTtsSynthesisFinished
    else onTtsSynthesisStarting(SynthesisService.instance.currentText.length)
    if (SynthesisService.ready) {
      if (SynthesisService.instance.prepared >= 0) onTtsSynthesisPrepared(SynthesisService.instance.prepared)
      if (SynthesisService.instance.currentStart >= 0)
        onTtsSynthesisCallback(SynthesisService.instance.currentStart, SynthesisService.instance.currentEnd)
    }
  }

  override def onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo) {
    if (v != inputText) return
    mainActivity.getMenuInflater.inflate(R.menu.input_text_styles, menu)
    menu.setHeaderTitle(R.string.action_style)
  }

  private def processTag(id: Int, source: CharSequence, selection: CharSequence = "", value: CharSequence = null)
    : Boolean = {
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
    } else if (processTag(item.getItemId | item.getGroupId, source, selection, item.getTitleCondensed))
      return super.onContextItemSelected(item)
    true
  }

  def onMenuItemClick(item: MenuItem) = {
    val ssml = App.enableSsmlDroid
    val mime = if (ssml) "application/ssml+xml" else "text/plain"
    item.getItemId match {
      case R.id.action_style =>
        selectionStart = inputText.getSelectionStart
        selectionEnd = inputText.getSelectionEnd
        registerForContextMenu(inputText)
        mainActivity.openContextMenu(inputText)
        unregisterForContextMenu(inputText)
        true
      case R.id.action_synthesize_to_file =>
        SynthesisService.read {
          val mime = SynthesisService.instance.engines.selectedEngine.getMimeType
          runOnUiThread(mainActivity.showSave(mime, App.getSaveFileName + '.' +
            MimeTypeMap.getSingleton.getExtensionFromMimeType(mime), SAVE_SYNTHESIS))
        }
        true
      case R.id.action_open =>
        try startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
          .setType(mime), OPEN_TEXT)
        catch {
          case e: ActivityNotFoundException => showToast(R.string.open_error_no_browser)
        }
        true
      case R.id.action_save =>
        val extension = if (ssml) ".ssml" else ".txt"
        var fileName = App.getSaveFileName
        if (!fileName.toLowerCase.endsWith(extension)) fileName += extension
        mainActivity.showSave(mime, fileName, SAVE_TEXT)
        true
      case R.id.action_settings =>
        mainActivity.showSettings
        true
      case _ => false
    }
  }

  private def getStartOffset = {
    val start = App.pref.getString("text.start", "beginning")
    if ("selection_start" == start) inputText.getSelectionStart
    else if ("selection_end" == start) inputText.getSelectionEnd else 0
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
        output = mainActivity.getContentResolver.openOutputStream(uri)
        output.write(inputText.getText.toString.getBytes)
      } catch {
        case e: IOException =>
          e.printStackTrace
          showToast(String.format(R.string.save_error, e.getMessage))
      } finally if (output != null) try output.close catch {
        case e: IOException => e.printStackTrace
      }
    case SAVE_SYNTHESIS =>
      SynthesisService.write(SynthesisService.instance.synthesizeToUri(getText, getStartOffset, uri), {
        case e: Exception =>
          e.printStackTrace
          showToast(String.format(R.string.synthesis_error, e.getMessage))
          SynthesisService.instance.stop
      })
  }
  override def onActivityResult(requestCode: Int, resultCode: Int, data: Intent) = requestCode match {
    case OPEN_TEXT =>
      if (resultCode == Activity.RESULT_OK) mainActivity.onNewIntent(data)
    case SAVE_TEXT | SAVE_SYNTHESIS =>
      if (resultCode == Activity.RESULT_OK) save(data.getData, requestCode)
    case OPEN_EARCON => if (resultCode == Activity.RESULT_OK) {
      val uri = data.getData
      if (Build.version >= 19)
        try mainActivity.getContentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        catch {
          case ignore: Exception =>
        }
      processTag(R.id.action_tts_earcon, inputText.getText, uri.toString)
    } else processTag(R.id.action_tts_earcon, inputText.getText)
    case _ => super.onActivityResult(requestCode, resultCode, data)
  }

  def onTtsSynthesisStarting(length: Int) = runOnUiThread {
    fab.setImageDrawable(R.drawable.ic_av_mic)
    menu.setGroupEnabled(R.id.disabled_when_synthesizing, false)
    textView.setText(inputText.getText, BufferType.EDITABLE)
    pager.setCurrentItem(1, false)
    inputMethodManager.hideSoftInputFromWindow(inputText.getWindowToken, InputMethodManager.HIDE_NOT_ALWAYS)
    progressBar.setIndeterminate(true)
    progressBar.setVisibility(View.VISIBLE)
    progressBar.setMax(length)
  }
  def onTtsSynthesisPrepared(end: Int) = runOnUiThread {
    if (progressBar.isIndeterminate) {
      progressBar.setIndeterminate(false)
      progressBar.setProgress(0)
    }
    progressBar.setSecondaryProgress(end)
  }
  def onTtsSynthesisCallback(s: Int, e: Int) = {
    var start = s
    var end = e
    if (SynthesisService.instance.mappings != null) {
      start = SynthesisService.instance.mappings.getSourceOffset(start, false)
      end = SynthesisService.instance.mappings.getSourceOffset(end, true)
    }
    if (end < start) end = start
    runOnUiThread {
      if (progressBar.isIndeterminate) {
        progressBar.setIndeterminate(false)
        progressBar.setSecondaryProgress(0)
      }
      progressBar.setProgress(s)
      inputText.setSelection(start, end)
      if (start != end) textView.getEditableText.setSpan(highlightSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      else textView.getEditableText.removeSpan(highlightSpan)
      textView.bringPointIntoView(end)
    }
  }
  def onTtsSynthesisError(start: Int, end: Int) = ()
  def onTtsSynthesisFinished = runOnUiThread {
    fab.setImageDrawable(R.drawable.ic_av_mic_none)
    menu.setGroupEnabled(R.id.disabled_when_synthesizing, true)
    pager.setCurrentItem(0, false)
    progressBar.setVisibility(View.INVISIBLE)
  }

  override def makeSnackbar(text: CharSequence, duration: Int = Snackbar.LENGTH_LONG, view: View = fab) =
    super.makeSnackbar(text, duration, view)
}