package tk.mygod.speech.tts

/**
 * @author Mygod
 */
trait OnTtsSynthesisCallbackListener {
  def onTtsSynthesisStarting(length: Int)
  def onTtsSynthesisPrepared(end: Int)
  def onTtsSynthesisCallback(start: Int, end: Int)
  def onTtsSynthesisError(start: Int, end: Int)
  def onTtsSynthesisFinished
}
