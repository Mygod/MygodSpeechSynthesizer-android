package tk.mygod.speech.tts

import android.content.{ContentUris, UriMatcher}
import android.net.Uri
import android.net.Uri.Builder
import tk.mygod.content.StubProvider

import scala.collection.mutable

/**
  * @author Mygod
  */
object EarconsProvider {
  private val authority = "tk.mygod.speech.tts.provider"
  private val uriMatcher = new UriMatcher(0)
  uriMatcher.addURI(authority, "earcons/#", 0)

  // Based on: http://stackoverflow.com/a/1660613/2245107
  private def hashCode(str: String) = {
    var h = 1125899906842597L
    for (c <- str) h = h * 31 + c
    h & 0x7FFFFFFFFFFFFFFFL // drop the minus sign
  }
  val uriMapper = new mutable.LongMap[Uri]
  def addUri(uri: Uri) = {
    val hash = hashCode(uri.toString)
    uriMapper(hash) = uri
    hash
  }
  def getUri(hash: Long) = ContentUris.appendId(new Builder().scheme("content").authority(authority).path("earcons"),
    hash).build
}

final class EarconsProvider extends StubProvider {
  import EarconsProvider._

  override def getType(uri: Uri) = uriMatcher.`match`(uri) match {
    case 0 => uriMapper.get(uri.getLastPathSegment.toLong) match {
      case Some(realUri) => super.getType(realUri)
      case None => throw new IllegalArgumentException("Unknown Uri: " + uri)
    }
    case _ => throw new IllegalArgumentException("Invalid Uri: " + uri)
  }
  override def openFile(uri: Uri, mode: String) = uriMatcher.`match`(uri) match {
    case 0 => uriMapper.get(uri.getLastPathSegment.toLong) match {
      case Some(realUri) => getContext.getContentResolver.openFileDescriptor(realUri, mode)
      case None => throw new IllegalArgumentException("Unknown Uri: " + uri)
    }
    case _ => throw new IllegalArgumentException("Invalid Uri: " + uri)
  }
}
