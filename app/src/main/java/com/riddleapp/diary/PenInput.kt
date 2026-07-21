package com.riddleapp.diary

import android.view.MotionEvent

/**
 * The diary is a pen-only surface: fingers and resting palms are ignored everywhere, not just on the
 * page. A flipped marker reports as an eraser and still counts as the pen.
 */
object PenInput {

    fun isPen(toolType: Int): Boolean =
        toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

    fun isEraser(toolType: Int): Boolean = toolType == MotionEvent.TOOL_TYPE_ERASER

    /** True when any pointer in this event is the pen — used by simple tap targets. */
    fun hasPen(event: MotionEvent): Boolean =
        (0 until event.pointerCount).any { isPen(event.getToolType(it)) }
}
