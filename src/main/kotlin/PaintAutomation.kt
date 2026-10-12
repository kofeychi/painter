import org.joml.Vector2i
import java.awt.Point
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal object PaintAutomation {
    private val runState = AtomicBoolean(false)
    val running: Boolean get() = runState.get()
    private val robot: Robot by lazy { Robot() }

    fun reserve(): Boolean = runState.compareAndSet(false, true)
    fun cancelReservation() { runState.set(false) }

    fun autoPaint(
        grid: TransformableGrid,
        palletes: PalletedImage,
        isPixelEnabled: (Vector2i) -> Boolean,
        picker: () -> Point,
        hider: (Boolean) -> Unit
    ) {
        var completedPixels = 0
        var paletteLayerIndex = 0

        try {
            hider(false)
            val validLayers = palletes.pallete.filter { it.key.alpha >= 128 }
            val totalPixels = validLayers.values.sumOf { coords -> coords.count(isPixelEnabled) }
            val totalLayers = validLayers.count { (_, coords) -> coords.any(isPixelEnabled) }
            progressHUD.start(totalPixels, totalLayers)
            for ((color, coords) in validLayers) {
                val coordinatesToPaint = coords.filter(isPixelEnabled)
                if (coordinatesToPaint.isEmpty()) continue
                paletteLayerIndex++
                progressHUD.setProgress(completedPixels, paletteLayerIndex)
                println("Selecting color $color (palette layer $paletteLayerIndex / $totalLayers)")
                selectColor(color, picker())

                for (position in coordinatesToPaint) {
                    val projected = grid.cached[position] ?: continue
                    click(projected.x.toInt(), projected.y.toInt())
                    Thread.sleep(20)
                    completedPixels++
                    progressHUD.setProgress(completedPixels, paletteLayerIndex)
                }
                Thread.sleep(50)
            }
            println("Layer painting completed!")
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (exception: Exception) {
            System.err.println("AutoPaint failed: ${exception.message}")
        } finally {
            progressHUD.stopOverlay()
            runState.set(false)
            hider(true)
        }
    }

    private fun click(x: Int, y: Int) {
        robot.autoDelay = 15
        robot.mouseMove(x, y)
        robot.mouseMove(x - 5, y)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    }

    private fun selectColor(color: ARGBColor, picker: Point) {
        repeat(4) {
            click(picker.x, picker.y)
            Thread.sleep(10)
        }

        robot.autoDelay = 10
        repeat(8) {
            robot.keyPress(KeyEvent.VK_RIGHT)
            robot.keyRelease(KeyEvent.VK_RIGHT)
        }

        val hex = String.format(Locale.US, "#%06X", (0xFFFFFF and color.color))
        typeString(hex, picker.x, picker.y)
    }

    private fun typeString(value: String, x: Int, y: Int) {
        robot.autoDelay = 20
        robot.mouseMove(x, y)
        robot.mouseMove(x - 5, y)
        repeat(value.length) {
            robot.keyPress(KeyEvent.VK_BACK_SPACE)
            robot.keyRelease(KeyEvent.VK_BACK_SPACE)
        }

        // Robot key codes describe physical keys, not arbitrary characters. In
        // particular '#' has no reliable key code across keyboard layouts, so
        // typing it with getExtendedKeyCodeForChar() silently skipped it. Paste
        // the color text through the system clipboard instead; this is layout
        // independent for hex fields and also handles other non-US characters.
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        var previousContents: Transferable? = null
        try {
            previousContents = clipboard.getContents(null)
            clipboard.setContents(StringSelection(value), null)
            robot.keyPress(KeyEvent.VK_CONTROL)
            robot.keyPress(KeyEvent.VK_V)
            robot.keyRelease(KeyEvent.VK_V)
            robot.keyRelease(KeyEvent.VK_CONTROL)
            // Allow the target application's paste event to consume the data
            // before restoring the user's previous clipboard contents.
            robot.delay(180)
        } catch (exception: Exception) {
            throw IllegalStateException(
                "Could not paste the color text into the target color field. Clipboard access is required to enter '#'.",
                exception
            )
        } finally {
            try {
                clipboard.setContents(previousContents ?: StringSelection(""), null)
            } catch (_: Exception) {
                // Clipboard restoration is best-effort; color entry is already sent.
            }
        }
    }
}
