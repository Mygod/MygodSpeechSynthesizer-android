package tk.mygod.speech.tts

import android.content.{ContentUris, ContentProvider, ContentValues, UriMatcher}
import android.net.Uri
import android.net.Uri.Builder
import tk.mygod.util.MimeUtils

import scala.collection.mutable

/**
  * @author Mygod
  */
object EarconsProvider {
  private val TAG = "EarconsProvider"
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

final class EarconsProvider extends ContentProvider {
  import EarconsProvider._

  def delete(uri: Uri, selection: String, selectionArgs: Array[String]) = throw new UnsupportedOperationException
  def insert(uri: Uri, values: ContentValues) = throw new UnsupportedOperationException
  def onCreate = true
  def query(uri: Uri, projection: Array[String], selection: String, selectionArgs: Array[String], sortOrder: String) =
    throw new UnsupportedOperationException
  def update(uri: Uri, values: ContentValues, selection: String, selectionArgs: Array[String]) =
    throw new UnsupportedOperationException

  def getType(uri: Uri) = uriMatcher.`match`(uri) match {
    case 0 => uriMapper.get(uri.getLastPathSegment.toLong) match {
      case Some(realUri) => if (realUri.getScheme == "file") MimeUtils.getMimeType(realUri.toString)
        else getContext.getContentResolver.getType(realUri)
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
