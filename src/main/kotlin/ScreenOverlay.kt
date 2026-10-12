import java.awt.*
import javax.swing.*

data class DrawingItem(
    val shape: Shape,
    val color: Color,
    val thickness: Float
)

class ScreenOverlay {

    val frame: JFrame = JFrame("ScreenOverlay")
    val drawPanel: DrawPanel = DrawPanel()

    var clickThroughEnabled: Boolean = false
        private set
    var clickThroughError: String? = null
        private set

    init {
        frame.isUndecorated = true
        frame.background = Color(0, 0, 0, 0)
        frame.isAlwaysOnTop = true

        frame.extendedState = JFrame.MAXIMIZED_BOTH

        frame.add(drawPanel)

        frame.focusableWindowState = false
    }

    var isVisible: Boolean
        get() = frame.isVisible
        set(value) {
            frame.isVisible = value
            // Some desktop window managers drop an overlay from the topmost group
            // when it is hidden and shown again. Re-assert the topmost flag after
            // showing it rather than relying only on the constructor setting.
            if (value) {
                frame.isAlwaysOnTop = true
                // Re-apply native input styles after a hidden/shown cycle; some
                // window managers can recreate or reset the native window state.
                if (clickThroughEnabled && WindowsWindowInput.isSupported()) {
                    try {
                        WindowsWindowInput.setClickThrough(frame, true)
                    } catch (exception: Exception) {
                        clickThroughError = exception.message ?: exception.javaClass.simpleName
                    }
                }
            }
        }

    fun showOnTop() {
        frame.isAlwaysOnTop = true
        frame.isVisible = true
        frame.isAlwaysOnTop = true
    }

    /** Enable or disable mouse pass-through for the full-screen preview window. */
    fun setClickThrough(enabled: Boolean): Boolean {
        if (!WindowsWindowInput.isSupported()) {
            clickThroughError = "Click-through is currently supported on Windows only."
            return false
        }
        return try {
            WindowsWindowInput.setClickThrough(frame, enabled)
            clickThroughEnabled = enabled
            clickThroughError = null
            true
        } catch (exception: Exception) {
            clickThroughError = exception.message ?: exception.javaClass.simpleName
            false
        }
    }

    fun addShape(shape: Shape, color: Color, thickness: Float) {
        drawPanel.addDrawingItem(DrawingItem(shape, color, thickness))
        drawPanel.repaint()
    }

    fun clear() {
        drawPanel.clear()
        drawPanel.repaint()
    }

    class DrawPanel : JPanel() {
        private val items = ArrayList<DrawingItem>()
        var on: ((Graphics) -> Unit)? = null

        init {
            isOpaque = false
            layout = null
        }

        fun addDrawingItem(item: DrawingItem) {
            items.add(item)
        }

        fun clear() {
            items.clear()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            on?.invoke(g)

            val g2d = g as Graphics2D

            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            for (item in items) {
                g2d.color = item.color
                g2d.stroke = BasicStroke(item.thickness)
                g2d.draw(item.shape)
            }
        }
    }
}