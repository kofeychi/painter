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