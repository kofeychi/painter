import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import org.joml.Matrix3f
import org.joml.Vector2f
import org.joml.Vector2i
import org.joml.Vector3f
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D

class TransformableGrid(rectangle: Rectangle2D) {
    var grid_dimensions: Vector2i = Vector2i(16, 16)
    var draggedCornerIndex: Int = -1

    companion object {
        const val CENTER_HANDLE_INDEX = 4
        const val RESIZE_HANDLE_START = 5
        const val RESIZE_OFFSET = 44.0
    }
    var HANDLE_SIZE: Int = 12
    val corners: Array<Point>
    var dots: Boolean = true
    var cells: Boolean = true
    var showGridLines: Boolean = true
    private var lastMousePos: Point? = null
    private var initialCorners: Array<Point>? = null
    val cached: Object2ObjectOpenHashMap<Vector2i, Vector2f> = Object2ObjectOpenHashMap()

    init {
        val ix = rectangle.minX.toInt()
        val iy = rectangle.minY.toInt()
        val mx = rectangle.maxX.toInt()
        val my = rectangle.maxY.toInt()

        corners = arrayOf(
            Point(ix, iy),
            Point(mx, iy),
            Point(mx, my),
            Point(ix, my)
        )
    }

    val centerPoint: Point
        get() {
            var sumX = 0
            var sumY = 0
            for (p in corners) {
                sumX += p.x
                sumY += p.y
            }
            return Point(sumX / 4, sumY / 4)
        }

    fun getResizeHandlePoint(cornerIndex: Int): Point {
        val corner = corners[cornerIndex]
        val center = centerPoint
        val dx = (corner.x - center.x).toDouble()
        val dy = (corner.y - center.y).toDouble()
        val dist = Math.hypot(dx, dy)
        if (dist == 0.0) return Point(corner)
        val nx = dx / dist
        val ny = dy / dist
        return Point(
            (corner.x + nx * RESIZE_OFFSET).toInt(),
            (corner.y + ny * RESIZE_OFFSET).toInt()
        )
    }

    fun mmousePressed(e: MouseEvent) {
        val hitThreshold = HANDLE_SIZE * 2

        for (i in corners.indices) {
            if (e.point.distance(corners[i]) < hitThreshold) {
                draggedCornerIndex = i
                lastMousePos = e.point
                return
            }
        }

        if (e.point.distance(centerPoint) < hitThreshold) {
            draggedCornerIndex = CENTER_HANDLE_INDEX
            lastMousePos = e.point
            return
        }

        for (i in 0 until 4) {
            val resizePoint = getResizeHandlePoint(i)
            if (e.point.distance(resizePoint) < hitThreshold) {
                draggedCornerIndex = RESIZE_HANDLE_START + i
                lastMousePos = e.point
                initialCorners = Array(4) { Point(corners[it]) }
                return
            }
        }
    }

    fun mmouseReleased(e: MouseEvent) {
        draggedCornerIndex = -1
        lastMousePos = null
        initialCorners = null
    }

    fun mmouseDragged(e: MouseEvent) {
        val currentMousePos = e.point
        val previousMousePos = lastMousePos ?: currentMousePos

        if (draggedCornerIndex in 0..3) {
            corners[draggedCornerIndex].location = currentMousePos
        } else if (draggedCornerIndex == CENTER_HANDLE_INDEX) {
            val dx = currentMousePos.x - previousMousePos.x
            val dy = currentMousePos.y - previousMousePos.y

            for (corner in corners) {
                corner.translate(dx, dy)
            }
        } else if (draggedCornerIndex in RESIZE_HANDLE_START until RESIZE_HANDLE_START + 4) {
            val cornerIdx = draggedCornerIndex - RESIZE_HANDLE_START
            val initCorners = initialCorners ?: return

            val anchorIdx = (cornerIdx + 2) % 4
            val anchor = initCorners[anchorIdx]

            val initialCorner = initCorners[cornerIdx]
            val origVx = (initialCorner.x - anchor.x).toDouble()
            val origVy = (initialCorner.y - anchor.y).toDouble()
            val origLenSq = origVx * origVx + origVy * origVy

            if (origLenSq > 0.0001) {
                val curVx = (currentMousePos.x - anchor.x).toDouble()
                val curVy = (currentMousePos.y - anchor.y).toDouble()

                var scale = (curVx * origVx + curVy * origVy) / origLenSq
                if (scale < 0.05) scale = 0.05

                for (i in 0 until 4) {
                    val relX = initCorners[i].x - anchor.x
                    val relY = initCorners[i].y - anchor.y
                    corners[i].x = (anchor.x + relX * scale).toInt()
                    corners[i].y = (anchor.y + relY * scale).toInt()
                }
            }
        }

        lastMousePos = currentMousePos
    }

    private fun calculateHomography(): Matrix3f {
        val x0 = corners[0].x.toFloat()
        val y0 = corners[0].y.toFloat()
        val x1 = corners[1].x.toFloat()
        val y1 = corners[1].y.toFloat()
        val x2 = corners[2].x.toFloat()
        val y2 = corners[2].y.toFloat()
        val x3 = corners[3].x.toFloat()
        val y3 = corners[3].y.toFloat()

        val dx1 = x1 - x2
        val dy1 = y1 - y2
        val dx2 = x3 - x2
        val dy2 = y3 - y2
        val sx = x0 - x1 + x2 - x3
        val sy = y0 - y1 + y2 - y3

        val denominator = dx1 * dy2 - dy1 * dx2
        val g = if (denominator != 0f) (sx * dy2 - sy * dx2) / denominator else 0f
        val h = if (denominator != 0f) (dx1 * sy - dy1 * sx) / denominator else 0f
        val a = x1 - x0 + g * x1
        val b = x3 - x0 + h * x3
        val d = y1 - y0 + g * y1
        val e = y3 - y0 + h * y3

        return Matrix3f(
            a, d, g,
            b, e, h,
            x0, y0, 1.0f
        )
    }

    fun paint(g: Graphics, current: PalletedImage, shouldDrawImage: Boolean) {
        recalculatePoints()
        val g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val homography = calculateHomography()

        if (shouldDrawImage) {
            for ((paletteKey, positions) in current.pallete) {
                if (paletteKey.alpha < 128) continue
                val color = Color(paletteKey.color, true)
                for (pos in positions) {
                    fillCell(g2d, homography, pos.x, pos.y, color)
                }
            }
        }

        if (showGridLines) {
            drawGrid(g2d, homography)
        }

        if (dots) {
            g2d.color = Color(0, 180, 255)
            for (i in 0 until grid_dimensions.x) {
                for (j in 0 until grid_dimensions.y) {
                    val pos = cached[Vector2i(i, j)]
                    if (pos != null) {
                        val dotSize = 4
                        g2d.fill(
                            Ellipse2D.Float(
                                pos.x - dotSize / 2f,
                                pos.y - dotSize / 2f,
                                dotSize.toFloat(),
                                dotSize.toFloat()
                            )
                        )
                    }
                }
            }
        }

        val center = centerPoint
        g2d.color = Color(255, 255, 255, 60)
        val stroke = g2d.stroke
        g2d.stroke = BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, floatArrayOf(4.0f, 4.0f), 0.0f)
        for (corner in corners) {
            g2d.drawLine(corner.x, corner.y, center.x, center.y)
        }
        g2d.stroke = stroke

        g2d.color = Color.WHITE
        for (p in corners) {
            g2d.fillOval(p.x - HANDLE_SIZE / 2, p.y - HANDLE_SIZE / 2, HANDLE_SIZE, HANDLE_SIZE)
        }

        for (i in 0 until 4) {
            val corner = corners[i]
            val resizeP = getResizeHandlePoint(i)

            g2d.color = Color(255, 170, 0, 160)
            g2d.drawLine(corner.x, corner.y, resizeP.x, resizeP.y)

            val rSize = HANDLE_SIZE - 2
            g2d.color = Color(255, 165, 0)
            g2d.fillRect(resizeP.x - rSize / 2, resizeP.y - rSize / 2, rSize, rSize)
            g2d.color = Color.WHITE
            g2d.drawRect(resizeP.x - rSize / 2, resizeP.y - rSize / 2, rSize, rSize)
        }

        val centerHandleSize = HANDLE_SIZE + 4
        g2d.color = Color(0, 220, 110)
        g2d.fillOval(center.x - centerHandleSize / 2, center.y - centerHandleSize / 2, centerHandleSize, centerHandleSize)
        g2d.color = Color.WHITE
        g2d.drawOval(center.x - centerHandleSize / 2, center.y - centerHandleSize / 2, centerHandleSize, centerHandleSize)
    }

    private fun fillCell(g2d: Graphics2D, h: Matrix3f, col: Int, row: Int, color: Color) {
        val denomX = if (grid_dimensions.x > 1) (grid_dimensions.x - 1).toFloat() else 1.0f
        val denomY = if (grid_dimensions.y > 1) (grid_dimensions.y - 1).toFloat() else 1.0f

        val u0 = (col - 0.5f) / denomX
        val v0 = (row - 0.5f) / denomY
        val u1 = (col + 0.5f) / denomX
        val v1 = (row + 0.5f) / denomY

        val coords = arrayOf(
            floatArrayOf(u0, v0),
            floatArrayOf(u1, v0),
            floatArrayOf(u1, v1),
            floatArrayOf(u0, v1)
        )

        val path = Path2D.Float()
        for (i in 0 until 4) {
            val p = Vector3f(coords[i][0], coords[i][1], 1.0f).mul(h)
            val px = if (p.z != 0f) p.x / p.z else p.x
            val py = if (p.z != 0f) p.y / p.z else p.y
            if (i == 0) {
                path.moveTo(px, py)
            } else {
                path.lineTo(px, py)
            }
        }
        path.closePath()

        g2d.color = color
        if (cells) {
            g2d.fill(path)
        }

        g2d.color = Color(255, 0, 0, 255)
        g2d.draw(path)
    }

    private fun drawGrid(g2d: Graphics2D, h: Matrix3f) {
        val originalStroke = g2d.stroke
        g2d.stroke = BasicStroke(1.0f)
        g2d.color = Color(255, 255, 255, 100)

        val denomX = if (grid_dimensions.x > 1) (grid_dimensions.x - 1).toFloat() else 1.0f
        val denomY = if (grid_dimensions.y > 1) (grid_dimensions.y - 1).toFloat() else 1.0f

        for (col in 0..grid_dimensions.x) {
            val u = (col - 0.5f) / denomX
            val vStart = (-0.5f) / denomY
            val vEnd = (grid_dimensions.y - 0.5f) / denomY

            val pStart = Vector3f(u, vStart, 1.0f).mul(h)
            val pEnd = Vector3f(u, vEnd, 1.0f).mul(h)

            val x1 = if (pStart.z != 0f) pStart.x / pStart.z else pStart.x
            val y1 = if (pStart.z != 0f) pStart.y / pStart.z else pStart.y
            val x2 = if (pEnd.z != 0f) pEnd.x / pEnd.z else pEnd.x
            val y2 = if (pEnd.z != 0f) pEnd.y / pEnd.z else pEnd.y

            g2d.drawLine(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
        }

        for (row in 0..grid_dimensions.y) {
            val v = (row - 0.5f) / denomY
            val uStart = (-0.5f) / denomX
            val uEnd = (grid_dimensions.x - 0.5f) / denomX

            val pStart = Vector3f(uStart, v, 1.0f).mul(h)
            val pEnd = Vector3f(uEnd, v, 1.0f).mul(h)

            val x1 = if (pStart.z != 0f) pStart.x / pStart.z else pStart.x
            val y1 = if (pStart.z != 0f) pStart.y / pStart.z else pStart.y
            val x2 = if (pEnd.z != 0f) pEnd.x / pEnd.z else pEnd.x
            val y2 = if (pEnd.z != 0f) pEnd.y / pEnd.z else pEnd.y

            g2d.drawLine(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
        }

        g2d.stroke = originalStroke
    }

    fun recalc(img: Vector2i) {
        grid_dimensions.x = img.x
        grid_dimensions.y = img.y
        recalculatePoints()
    }

    fun recalculatePoints() {
        val h = calculateHomography()
        cached.clear()
        val denomX = if (grid_dimensions.x > 1) (grid_dimensions.x - 1).toFloat() else 1.0f
        val denomY = if (grid_dimensions.y > 1) (grid_dimensions.y - 1).toFloat() else 1.0f

        for (x in 0 until grid_dimensions.x) {
            for (y in 0 until grid_dimensions.y) {
                val u = if (grid_dimensions.x > 1) x.toFloat() / denomX else 0.5f
                val v = if (grid_dimensions.y > 1) y.toFloat() / denomY else 0.5f

                val projected = Vector3f(u, v, 1.0f).mul(h)

                val screenX = if (projected.z != 0f) projected.x / projected.z else projected.x
                val screenY = if (projected.z != 0f) projected.y / projected.z else projected.y

                cached[Vector2i(x, y)] = Vector2f(screenX, screenY)
            }
        }
    }

    fun adapter(overlay: ScreenOverlay): MouseAdapter = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent?) {
            e?.let { mmousePressed(it) }
        }

        override fun mouseReleased(e: MouseEvent?) {
            e?.let { mmouseReleased(it) }
        }

        override fun mouseDragged(e: MouseEvent?) {
            e?.let {
                mmouseDragged(it)
                overlay.frame.repaint()
            }
        }
    }
}