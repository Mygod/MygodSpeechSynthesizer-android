package tk.mygod.text

import scala.collection.mutable.ArrayBuffer

/**
 * Add markers and convert offsets from two texts super conveniently!
 * @author Mygod
 */
class TextMappings {
  private val mappings = new ArrayBuffer[TextMapping]
  addMapping(0, 0)

  /**
   * Add a mapping between two offsets.
   * @param source Source offset.
   * @param target Target offset.
   */
  def addMapping(source: Int, target: Int) {
    val size = mappings.size
    if (size > 0) {
      val last = mappings(size - 1)
      if (source == last.sourceOffset) {
        last.targetOffset = target
        return
      }
    }
    mappings.append(new TextMapping(source, target))
  }

  /**
   * Get source offset from text offset. Takes O(log n) where n is the number of tags. Thread-safe.
   * @param targetOffset Target offset.
   * @param preferLeft If there is an tag at the specified offset, go as left as possible.
   *                   Otherwise, go as right as possible.
   * @return Source offset.
   */
  def getSourceOffset(targetOffset: Int, preferLeft: Boolean) = {
    var l = 0
    var r = mappings.size
    while (l < r) {
      val mid = (l + r) >> 1
      val pos = mappings(mid).targetOffset
      if (targetOffset < pos || targetOffset == pos && preferLeft) r = mid else l = mid + 1
    }
    val mapping = mappings(if (preferLeft) l else l - 1)
    mapping.sourceOffset + targetOffset - mapping.targetOffset
  }

  /**
   * Get target offset from text offset. Takes O(log n) where n is the number of tags. Thread-safe.
   * @param sourceOffset Source offset.
   * @return Target offset.
   */
  def getTargetOffset(sourceOffset: Int) = {
    var l = 0
    var r = mappings.size
    while (l < r) {
      val mid = (l + r) >> 1
      val pos = mappings(mid).sourceOffset
      if (sourceOffset == pos) {
        l = mid
        r = mid
      } else if (sourceOffset < pos) r = mid else l = mid + 1
    }
    val mapping = mappings(l)
    mapping.targetOffset + sourceOffset - mapping.sourceOffset
  }
}
