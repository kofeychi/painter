import java.awt.Color
import java.awt.Graphics
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

data class ColoredPoint(val color: ARGBColor, val point: Point)

class Points {
    val points = mutableMapOf<Int, ColoredPoint>()
    var draggedCornerIndex: Int = -1
    var HANDLE_SIZE: Int = 12
    var onSet: (Int, ColoredPoint) -> Unit = { _, _ -> }
    var onRelease: () -> Unit = {}

    fun mmousePressed(e: MouseEvent) {
        for ((key, coloredPoint) in points) {
            if (e.point.distance(coloredPoint.point) < HANDLE_SIZE * 2) {
                draggedCornerIndex = key
                break
            }
        }
    }

    fun mmouseReleased(e: MouseEvent) {
        draggedCornerIndex = -1
    }

    fun mmouseDragged(e: MouseEvent) {
        if (draggedCornerIndex != -1) {
            val target = points[draggedCornerIndex]
            if (target != null) {
                target.point.location = e.point
                onSet(draggedCornerIndex, target)
            }
        }
    }

    fun draw(grap: Graphics) {
        for (pp in points.values) {
            val p = pp.point
            grap.color = Color(pp.color.red, pp.color.green, pp.color.blue)
            grap.fillOval(p.x - HANDLE_SIZE / 2, p.y - HANDLE_SIZE / 2, HANDLE_SIZE, HANDLE_SIZE)
        }
    }

    fun adapter(overlay: ScreenOverlay): MouseAdapter = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent?) {
            e?.let { mmousePressed(it) }
        }

        override fun mouseReleased(e: MouseEvent?) {
            e?.let { mmouseReleased(it) }
            onRelease()
        }

        override fun mouseDragged(e: MouseEvent?) {
            e?.let {
                mmouseDragged(it)
                overlay.frame.repaint()
            }
        }
    }
}