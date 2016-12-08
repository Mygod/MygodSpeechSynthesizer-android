package be.mygod.widget

import android.content.Context
import android.support.design.widget.{CoordinatorLayout, FloatingActionButton}
import android.util.AttributeSet
import android.view.View

/**
  * @author Mygod
  */
class ScrollingFloatingActionButtonBehavior(context: Context, attrs: AttributeSet)
  extends FloatingActionButton.Behavior {
  override def onStartNestedScroll(parent: CoordinatorLayout, child: FloatingActionButton, directTargetChild: View,
                                   target: View, nestedScrollAxes: Int) = true
  override def onNestedScroll(parent: CoordinatorLayout, child: FloatingActionButton, target: View, dxConsumed: Int,
                              dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int): Unit = {
    super.onNestedScroll(parent, child, target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed)
    val dy = dyConsumed + dyUnconsumed
    if (dy < 0) child.show() else if (dy > 0) child.hide()
  }
}
