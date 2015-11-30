package tk.mygod.text

import java.io.StringReader
import java.lang.reflect.Field
import java.nio.CharBuffer

import android.os.{Build, PersistableBundle}
import android.text.style.TtsSpan
import android.text.{Editable, SpannableStringBuilder, Spanned}
import junit.framework.Assert
import org.xml.sax._
import org.xml.sax.helpers.{DefaultHandler, XMLReaderFactory}

import scala.collection.mutable

/**
 * SsmlDroid™.
 * @author Mygod
 */
object SsmlDroid {
  private class Tag(val Name: String, val Span: AnyRef = null, val Position: Int = 0) { }

  object Parser {
    private val months = Array("january", "february", "march", "april", "may", "june",
                               "july", "august", "september", "october", "november", "december")
    private val weekdays = Array("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

    private def parseInt(value: String, patterns: Array[String], directOffset: Int, patternOffset: Int): Int = {
      try return value.toInt + directOffset catch {
        case ignored: NumberFormatException =>
      }
      if (patterns != null) {
        val length = patterns.length
        var i = 0
        while (i < length) {
          if (patterns(i).equalsIgnoreCase(value)) return i + patternOffset
          i += 1
        }
      }
      throw new SAXNotRecognizedException("Unrecognized input: " + value)
    }
  }

  class Parser(private val source: String, private val customHandler: SsmlDroid.TagHandler,
               private val reader: XMLReader, private val ignoreSingleLineBreaks: Boolean) extends DefaultHandler {
    private val treeStack = new mutable.Stack[SsmlDroid.Tag]
    private var locator: Locator = null
    private var theCurrentLine: Field = null
    private var theCurrentColumn: Field = null
    private var lineNumber: Int = 0
    private var offset: Int = 0
    var Mappings: TextMappings = new TextMappings
    var Result: SpannableStringBuilder = new SpannableStringBuilder

    override def setDocumentLocator(l: Locator) {
      val rollback = locator
      try {
        locator = l
        val c = locator.getClass
        theCurrentLine = c.getDeclaredField("theCurrentLine")
        theCurrentLine.setAccessible(true)
        theCurrentColumn = c.getDeclaredField("theCurrentColumn")
        theCurrentColumn.setAccessible(true)
      }
      catch {
        case exc: NoSuchFieldException =>
          exc.printStackTrace
          locator = rollback
      }
    }

    override def startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
      val name = localName.toLowerCase
      if (customHandler != null && customHandler.handleTag(true, localName, Result, reader)) return
      if ("earcon" == name) {
        treeStack.push(new SsmlDroid.Tag("earcon", new EarconSpan, Result.length))
        return
      }
      if (Build.VERSION.SDK_INT < 21) {
        treeStack.push(new SsmlDroid.Tag(name))
        return
      }
      val bundle = new PersistableBundle
      var temp: String = null
      if (("cardinal" == name) || ("ordinal" == name)) {
        temp = attributes.getValue("number")
        if (temp == null) throw new AttributeMissingException(name + "/@number")
        bundle.putString(TtsSpan.ARG_NUMBER, temp)
      } else if ("date" == name) {
        temp = attributes.getValue("month")
        val month = temp != null
        if (month) bundle.putInt(TtsSpan.ARG_MONTH, Parser.parseInt(temp, Parser.months, 1, 0))
        temp = attributes.getValue("year")
        if (temp != null) bundle.putInt(TtsSpan.ARG_YEAR, Parser.parseInt(temp, null, 0, 0))
        else if (!month) throw new AttributeMissingException("date/@month | @year")
        temp = attributes.getValue("day")
        val day = temp != null
        if (day) bundle.putInt(TtsSpan.ARG_DAY, Parser.parseInt(temp, null, 0, 0))
        else if (!month) throw new AttributeMissingException("date/@month | @day")
        temp = attributes.getValue("weekday")
        if (temp != null) bundle.putInt(TtsSpan.ARG_WEEKDAY, Parser.parseInt(temp, Parser.weekdays, 0, 1))
        else if (!day) throw new AttributeMissingException("date/@day | @weekday")
      } else if (("decimal" == name) || ("money" == name)) {
        temp = attributes.getValue("integer_part")
        if (temp == null) throw new AttributeMissingException(name + "/@integer_part")
        bundle.putString(TtsSpan.ARG_INTEGER_PART, temp)
        temp = attributes.getValue("fractional_part")
        if (temp == null) throw new AttributeMissingException(name + "/@fractional_part")
        bundle.putString(TtsSpan.ARG_FRACTIONAL_PART, temp)
        temp = attributes.getValue("currency")
        if (temp != null) bundle.putString(TtsSpan.ARG_CURRENCY, temp)
        else if ("money" == name) throw new AttributeMissingException("money/@fractional_part")
        temp = attributes.getValue("quantity")
        if (temp != null) bundle.putString(TtsSpan.ARG_QUANTITY, temp)
      } else if ("digits" == name) {
        temp = attributes.getValue("digits")
        if (temp != null) bundle.putString(TtsSpan.ARG_DIGITS, temp)
      } else if ("electronic" == name) {
        var notSet = true
        temp = attributes.getValue("protocol")
        if (temp != null) {
          bundle.putString(TtsSpan.ARG_PROTOCOL, temp)
          notSet = false
        }
        temp = attributes.getValue("username")
        if (temp != null) {
          bundle.putString(TtsSpan.ARG_USERNAME, temp)
          notSet = false
        }
        temp = attributes.getValue("password")
        if (temp != null) {
          bundle.putString(TtsSpan.ARG_PASSWORD, temp)
          notSet = false
        }
        temp = attributes.getValue("domain")
        if (temp != null) {
          bundle.putString(TtsSpan.ARG_DOMAIN, temp)
          notSet = false
        }
        temp = attributes.getValue("port")
        if (temp != null) {
          bundle.putInt(TtsSpan.ARG_PORT, Parser.parseInt(temp, null, 0, 0))
          notSet = false
        }
        temp = attributes.getValue("path")
        if (temp != null) {
          bundle.putString(TtsSpan.ARG_PATH, temp)
          notSet = false
        }
        temp = attributes.getValue("query_string")
        if (temp != null) {
          bundle.putString(TtsSpan.ARG_QUERY_STRING, temp)
          notSet = false
        }
        temp = attributes.getValue("fragment_id")
        if (temp != null) {
          bundle.putString(TtsSpan.ARG_FRAGMENT_ID, temp)
          notSet = false
        }
        if (notSet) throw new AttributeMissingException("electronic/@protocol | @username | @password | @domain | " +
                                                        "@port | @path | @query_string | @fragment_id")
      } else if ("fraction" == name) {
        temp = attributes.getValue("numerator")
        if (temp == null) throw new AttributeMissingException("fraction/@numerator")
        bundle.putString(TtsSpan.ARG_NUMERATOR, temp)
        temp = attributes.getValue("denominator")
        if (temp == null) throw new AttributeMissingException("fraction/@denominator")
        bundle.putString(TtsSpan.ARG_DENOMINATOR, temp)
        temp = attributes.getValue("integer_part")
        if (temp != null) bundle.putString(TtsSpan.ARG_INTEGER_PART, temp)
      } else if ("measure" == name) {
        temp = attributes.getValue("number")
        if (temp == null) {
          temp = attributes.getValue("integer_part")
          val integer_part = temp != null
          if (integer_part) bundle.putString(TtsSpan.ARG_INTEGER_PART, temp)
          temp = attributes.getValue("fractional_part")
          if (temp == null) {
            temp = attributes.getValue("numerator")
            if (temp == null) throw new AttributeMissingException("measure/@numerator")
            bundle.putString(TtsSpan.ARG_NUMERATOR, temp)
            temp = attributes.getValue("denominator")
            if (temp == null) throw new AttributeMissingException("measure/@denominator")
            bundle.putString(TtsSpan.ARG_DENOMINATOR, temp)
          } else {
            bundle.putString(TtsSpan.ARG_FRACTIONAL_PART, temp)
            if (!integer_part) throw new AttributeMissingException("measure/@integer_part")
          }
        } else bundle.putString(TtsSpan.ARG_NUMBER, temp)
        temp = attributes.getValue("unit")
        if (temp == null) throw new AttributeMissingException("measure/@unit")
        bundle.putString(TtsSpan.ARG_UNIT, temp)
      } else if ("telephone" == name) {
        temp = attributes.getValue("number_parts")
        if (temp == null) throw new AttributeMissingException("telephone/@number_parts")
        bundle.putString(TtsSpan.ARG_NUMBER_PARTS, temp)
        temp = attributes.getValue("country_code")
        if (temp != null) bundle.putString(TtsSpan.ARG_COUNTRY_CODE, temp)
        temp = attributes.getValue("extension")
        if (temp != null) bundle.putString(TtsSpan.ARG_EXTENSION, temp)
      } else if ("text" == name) {
        temp = attributes.getValue("text")
        if (temp != null) bundle.putString(TtsSpan.ARG_TEXT, temp)
      } else if ("time" == name) {
        temp = attributes.getValue("hours")
        if (temp == null) throw new AttributeMissingException("time/@hours")
        bundle.putInt(TtsSpan.ARG_HOURS, Parser.parseInt(temp, null, 0, 0))
        temp = attributes.getValue("minutes")
        if (temp == null) throw new AttributeMissingException("time/@minutes")
        bundle.putInt(TtsSpan.ARG_MINUTES, Parser.parseInt(temp, null, 0, 0))
      } else if ("verbatim" == name) {
        temp = attributes.getValue("verbatim")
        if (temp == null) throw new AttributeMissingException("verbatim/@verbatim")
        bundle.putString(TtsSpan.ARG_VERBATIM, temp)
      } else {
        treeStack.push(new SsmlDroid.Tag(name))
        return
      }
      temp = attributes.getValue("gender")
      if (temp != null) bundle.putString(TtsSpan.ARG_GENDER, "android." + temp)
      temp = attributes.getValue("animacy")
      if (temp != null) bundle.putString(TtsSpan.ARG_ANIMACY, "android." + temp)
      temp = attributes.getValue("multiplicity")
      if (temp != null) bundle.putString(TtsSpan.ARG_MULTIPLICITY, "android." + temp)
      temp = attributes.getValue("case")
      if (temp != null) bundle.putString(TtsSpan.ARG_CASE, "android." + temp)
      treeStack.push(new SsmlDroid.Tag(name, new TtsSpan("android.type." + name, bundle), Result.length))
    }

    override def endElement(uri: String, localName: String, qName: String) {
      val tag = treeStack.pop
      if (tag.Span != null) Result.setSpan(tag.Span, tag.Position, Result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      else if (customHandler != null) customHandler.handleTag(false, localName, Result, reader)
    }

    override def characters(ch: Array[Char], start: Int, length: Int) {
      Assert.assertNotNull(locator)
      Assert.assertEquals(start, 0)
      var line = 0
      var column = 0
      try {
        line = theCurrentLine.get(locator).asInstanceOf[Integer]
        column = theCurrentColumn.get(locator).asInstanceOf[Integer]
      } catch {
        case exc: IllegalAccessException => throw new SAXException(exc)
      }
      while (lineNumber < line) {
        if (source.charAt(offset) != '\n') offset = source.indexOf('\n', offset)
        if (offset < 0 || offset >= source.length) throw new SAXException("Line number overflow.")
        offset += 1
        lineNumber += 1
      }
      var i = offset + column - 8
      val j = Result.length
      Mappings.addMapping(i - length, j)
      Mappings.addMapping(i, j + length)
      i = 0
      if (ignoreSingleLineBreaks)
        for (i <- 1 until length - 1) if (ch(i - 1) != '\n' && ch(i) == '\n' && ch(i + 1) != '\n') ch(i) = ' '
      Result.append(CharBuffer.wrap(ch, start, length))
    }
  }

  def fromSsml(source: String, ignoreSingleLineBreaks: Boolean = false, customHandler: SsmlDroid.TagHandler = null) = {
    val src = String.format("<speak>%s</speak>", source)
    val reader = XMLReaderFactory.createXMLReader("org.ccil.cowan.tagsoup.Parser")
    val result = new SsmlDroid.Parser(src, customHandler, reader, ignoreSingleLineBreaks)
    reader.setContentHandler(result)
    reader.parse(new InputSource(new StringReader(src)))
    result
  }

  trait TagHandler {
    def handleTag(opening: Boolean, tag: String, output: Editable, xmlReader: XMLReader): Boolean
  }
}
