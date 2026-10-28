package pl.adrros.bettercodereview.review.files

import com.intellij.ui.ExperimentalUI
import com.intellij.ui.JBColor
import com.intellij.ui.paint.RectanglePainter2D
import com.intellij.ui.tree.ui.DefaultTreeUI
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.Timer
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The gently pulsing purple tint and left bar on the row of the file open in the editor. Shared by the pull
 * request's change tree and the Commit tool window's tree. The pulse runs only while a marked row was painted
 * on a showing [owner], and repaints just that row.
 */
class CurrentFileMark(private val owner: JComponent) {
    private var phase = 0
    private var lastMark: Rectangle? = null
    private val pulse = Timer(PULSE_TICK_MS) {
        // A hidden parent skips painting, so painted() would never stop the timer.
        if (!owner.isShowing) {
            stop()
            return@Timer
        }
        phase = (phase + PULSE_TICK_MS) % PULSE_PERIOD_MS
        lastMark?.let { owner.repaint(it) } ?: owner.repaint()
    }

    val isPulsing: Boolean get() = pulse.isRunning

    /** The current file's row as of the last paint. */
    val markedRow: Rectangle? get() = lastMark

    /** True for the pulse's own repaint, which covers only the marked row. */
    fun isPulseRepaint(clip: Rectangle?): Boolean = clip != null && lastMark?.contains(clip) == true

    fun stop() {
        pulse.stop()
    }

    /** Called at the end of every paint with the current file's row, painted or not, so a partial repaint keeps the pulse alive. */
    fun painted(marked: Rectangle?) {
        lastMark = marked
        if (marked != null && owner.isShowing) pulse.start() else pulse.stop()
    }

    /** Alpha of the tint at this moment: a sine wave between the two limits. */
    private fun tintAlpha(): Int {
        val wave = (sin(2 * PI * phase / PULSE_PERIOD_MS) + 1) / 2
        return (TINT_ALPHA_MIN + wave * (TINT_ALPHA_MAX - TINT_ALPHA_MIN)).roundToInt()
    }

    fun paint(g: Graphics, row: Rectangle, visible: Rectangle) {
        val g2 = g.create() as Graphics2D
        try {
            val newUi = ExperimentalUI.isNewUI()
            val inset = if (newUi) JBUI.scale(DefaultTreeUI.HORIZONTAL_SELECTION_OFFSET) else 0
            val arc = if (newUi) JBUI.CurrentTheme.Tree.ARC.get().toDouble() else 0.0
            g2.color = Color(HIGHLIGHT.red, HIGHLIGHT.green, HIGHLIGHT.blue, tintAlpha())
            RectanglePainter2D.FILL.paint(
                g2, (visible.x + inset).toDouble(), row.y.toDouble(), (visible.width - 2 * inset).toDouble(), row.height.toDouble(), arc,
            )
            g2.color = HIGHLIGHT
            g2.fillRect(visible.x, row.y, JBUI.scale(BAR_WIDTH), row.height)
        } finally {
            g2.dispose()
        }
    }

    companion object {
        const val BAR_WIDTH = 4
        private const val TINT_ALPHA_MIN = 90
        private const val TINT_ALPHA_MAX = 160
        private const val PULSE_PERIOD_MS = 1600
        private const val PULSE_TICK_MS = 40

        /** Saturated purple, overridable by themes; the tint is drawn with alpha, so text stays readable underneath. */
        val HIGHLIGHT: Color = JBColor.namedColor("BetterCodeReview.CurrentFile.background", JBColor(Color(0x7C, 0x3A, 0xED), Color(0xA7, 0x8B, 0xFA)))
    }
}
