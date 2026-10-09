import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.*
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import org.joml.Vector2i
import java.awt.*
import java.awt.event.*
import java.awt.geom.Rectangle2D
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.LockSupport
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.Timer
import javax.swing.border.EmptyBorder
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.io.path.Path
import kotlin.system.exitProcess

fun init(body: () -> Unit) {
    CompletableFuture.runAsync {
        GlobalScreen.registerNativeHook()
        body()
        while (!Thread.interrupted()) {
            LockSupport.park()
        }
        GlobalScreen.unregisterNativeHook()
    }
}

data class Config(
    var pickerPos: Vector2i = Vector2i(10, 10),
)

val cfg = SimpleGsonConfigHandler(
    clazz = Config::class.java,
    filePath = Path(".").resolve("cfg.json"),
    defaultSupplier = ::Config,
)

class PaintProgressOverlay : JWindow() {
    private val timeFormat = NumberFormat.getIntegerInstance(Locale.US)

    private val titleLabel = JLabel("AutoPaint Active")
    private val chunkLabel = JLabel("Chunk: 1 / 1")
    private val elapsedLabel = JLabel("Elapsed: 00:00")
    private val remainingLabel = JLabel("Remaining: Calculating...")
    private val layerLabel = JLabel("Layer: 0 / 0")
    private val countLabel = JLabel("0 / 0 (0.0%)")
    private val progressBar = JProgressBar(0, 100)

    private var startTimeMs: Long = 0L
    private var totalPixels: Int = 0
    @Volatile private var completedPixels: Int = 0
    @Volatile private var totalLayers: Int = 0
    @Volatile private var currentLayer: Int = 0

    private var updateTimer: Timer? = null

    init {
        isAlwaysOnTop = true
        type = Window.Type.UTILITY

        val contentPane = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Color(20, 24, 30, 230)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(60, 130, 240, 180), 1),
                EmptyBorder(10, 14, 10, 14)
            )
        }

        fun styleLabel(label: JLabel, fontDelta: Float = 0f, bold: Boolean = false, color: Color = Color.WHITE) {
            label.alignmentX = Component.LEFT_ALIGNMENT
            label.foreground = color
            var font = label.font.deriveFont(fontDelta + 12f)
            if (bold) font = font.deriveFont(Font.BOLD)
            label.font = font
        }

        styleLabel(titleLabel, 2f, bold = true, color = Color(100, 200, 255))
        styleLabel(chunkLabel, 1f, bold = true, color = Color(150, 220, 150))
        styleLabel(elapsedLabel, 0f, color = Color(220, 220, 220))
        styleLabel(remainingLabel, 1f, bold = true, color = Color(255, 200, 80))
        styleLabel(layerLabel, -1f, color = Color(180, 180, 180))
        styleLabel(countLabel, -1f, color = Color(180, 180, 180))

        progressBar.apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isStringPainted = false
            preferredSize = Dimension(200, 8)
            maximumSize = Dimension(220, 8)
            foreground = Color(60, 160, 240)
            background = Color(40, 45, 55)
            border = BorderFactory.createEmptyBorder()
        }

        contentPane.add(titleLabel)
        contentPane.add(chunkLabel)
        contentPane.add(Box.createVerticalStrut(6))
        contentPane.add(elapsedLabel)
        contentPane.add(remainingLabel)
        contentPane.add(Box.createVerticalStrut(6))
        contentPane.add(progressBar)
        contentPane.add(Box.createVerticalStrut(4))
        contentPane.add(layerLabel)
        contentPane.add(countLabel)

        this.contentPane = contentPane
        pack()
        setLocation(20, 20)
    }

    fun updateChunkInfo(currentChunk: Int, totalChunks: Int) {
        SwingUtilities.invokeLater {
            chunkLabel.text = "Chunk: $currentChunk / $totalChunks"
            pack()
        }
    }

    fun start(totalPx: Int, layersCount: Int) {
        SwingUtilities.invokeLater {
            this.totalPixels = totalPx
            this.completedPixels = 0
            this.totalLayers = layersCount
            this.currentLayer = 0
            this.startTimeMs = System.currentTimeMillis()

            progressBar.value = 0
            remainingLabel.text = "Remaining: Calculating..."
            updateDisplay()

            updateTimer?.stop()
            updateTimer = Timer(200) { updateDisplay() }
            updateTimer?.start()

            isVisible = true
        }
    }

    fun setProgress(completedPx: Int, layerIdx: Int) {
        this.completedPixels = completedPx
        this.currentLayer = layerIdx
    }

    fun stopOverlay() {
        SwingUtilities.invokeLater {
            updateTimer?.stop()
            isVisible = false
        }
    }

    private fun updateDisplay() {
        if (startTimeMs == 0L) return

        val elapsedMs = System.currentTimeMillis() - startTimeMs
        elapsedLabel.text = "Elapsed: ${formatDuration(elapsedMs)}"

        if (completedPixels > 0 && totalPixels > 0) {
            val msPerPixel = elapsedMs.toDouble() / completedPixels
            val remainingPixels = totalPixels - completedPixels
            val remainingMs = (remainingPixels * msPerPixel).toLong()

            remainingLabel.text = "Remaining: ${formatDuration(remainingMs)}"

            val pct = (completedPixels.toDouble() / totalPixels.toDouble()) * 100.0
            progressBar.value = pct.toInt()

            val formattedDone = timeFormat.format(completedPixels)
            val formattedTotal = timeFormat.format(totalPixels)
            countLabel.text = "$formattedDone / $formattedTotal (${String.format(Locale.US, "%.1f", pct)}%)"
        } else {
            remainingLabel.text = "Remaining: Calculating..."
            progressBar.value = 0
            countLabel.text = "0 / ${timeFormat.format(totalPixels)} (0.0%)"
        }

        layerLabel.text = "Layer: $currentLayer / $totalLayers"
        pack()
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSec = ms / 1000
        val seconds = totalSec % 60
        val minutes = (totalSec / 60) % 60
        val hours = totalSec / 3600

        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}

val progressHUD = PaintProgressOverlay()
@Volatile var running = false

fun main() {
    val chooser = JFileChooser().apply {
        currentDirectory = Path(".").toAbsolutePath().toFile()
        fileFilter = FileNameExtensionFilter("PNG Images", "png")
    }

    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return

    var pal = PalletedImage.extractPalletedImage(ImageIO.read(chooser.selectedFile))

    pal = pal.quantize(16)

    val chunks = pal.splitIntoRegions(51, 51)

    for ((pallete, size) in chunks) {
        fun idx(i: Vector2i): Int {
            return i.x + (i.y * size.x)
        }
        val comparator = Comparator.comparingInt(::idx)
        for ((col, positions) in pallete) {
            pallete[col] = ObjectArrayList(positions.sortedWith(comparator))
        }
    }
    var currentChunkIndex = 0

    var grid: TransformableGrid? = null
    val overlay = ScreenOverlay()
    val points = Points()

    val cfgg = cfg.get()
    points.points[0] = ColoredPoint(ARGBColor.ofRGB(255, 0, 0), Point(cfgg.pickerPos.x, cfgg.pickerPos.y))
    points.onSet = { idx, p ->
        if (idx == 0) cfgg.pickerPos = Vector2i(p.point.x, p.point.y)
    }

    fun activeChunk(): PalletedImage = chunks[currentChunkIndex]

    SwingUtilities.invokeLater {
        overlay.isVisible = true
        val bounds = overlay.frame.bounds
        val center = Vector2i(bounds.width / 2, bounds.height / 2)
        val size = 256

        grid = TransformableGrid(
            Rectangle2D.Float(
                (center.x - size).toFloat(),
                (center.y - size).toFloat(),
                (size * 2).toFloat(),
                (size * 2).toFloat()
            )
        ).apply {
            recalc(activeChunk().size)
        }

        val m = grid.adapter(overlay)
        val pm = points.adapter(overlay)

        overlay.drawPanel.addMouseListener(pm)
        overlay.drawPanel.addMouseMotionListener(pm)
        overlay.drawPanel.addMouseListener(m)
        overlay.drawPanel.addMouseMotionListener(m)

        overlay.drawPanel.on = { g2d ->
            grid.paint(g2d, activeChunk(), true)
            points.draw(g2d)
        }

        progressHUD.updateChunkInfo(currentChunkIndex + 1, chunks.size)
    }

    init {
        GlobalScreen.addNativeKeyListener(object : NativeKeyListener {
            override fun nativeKeyPressed(nativeEvent: NativeKeyEvent) {
                when (nativeEvent.keyCode) {
                    NativeKeyEvent.VC_DELETE -> {
                        cfg.save()
                        println("Exiting Application...")
                        exitProcess(0)
                    }

                    NativeKeyEvent.VC_INSERT -> {
                        if (!running) {
                            val active = activeChunk()
                            grid?.recalc(active.size)
                            Thread {
                                autoPaint(grid!!, active) { draw ->
                                    overlay.isVisible = draw
                                    overlay.frame.repaint()
                                }
                            }.start()
                        }
                    }

                    NativeKeyEvent.VC_PAGE_DOWN -> {
                        if (!running) {
                            currentChunkIndex = (currentChunkIndex + 1) % chunks.size
                            val current = activeChunk()
                            SwingUtilities.invokeLater {
                                grid?.recalc(current.size)
                                progressHUD.updateChunkInfo(currentChunkIndex + 1, chunks.size)
                                println("Switched to Chunk ${currentChunkIndex + 1}/${chunks.size} (Size: ${current.size.x}x${current.size.y})")
                                overlay.frame.repaint()
                            }
                        }
                    }
                }
            }
        })
    }
}

fun autoPaint(
    grid: TransformableGrid,
    palletes: PalletedImage,
    hider: (Boolean) -> Unit
) {
    if (running) return
    running = true
    hider(false)

    val validLayers = palletes.pallete.filter { it.key.alpha >= 128 }
    val totalPixels = validLayers.values.sumOf { it.size }
    val totalLayers = validLayers.size

    progressHUD.start(totalPixels, totalLayers)

    var completedPixels = 0
    var layerIndex = 0

    try {
        for ((color, coords) in validLayers) {
            layerIndex++
            progressHUD.setProgress(completedPixels, layerIndex)

            println("Selecting color $color (Layer $layerIndex / $totalLayers)")
            selectColor(color)

            for (position in coords) {
                val pos = grid.cached[position] ?: continue
                click(pos.x.toInt(), pos.y.toInt())
                Thread.sleep(20)

                completedPixels++
                progressHUD.setProgress(completedPixels, layerIndex)
            }
            Thread.sleep(50)
        }
        println("Chunk painting completed!")
    } finally {
        progressHUD.stopOverlay()
        running = false
        hider(true)
    }
}

val bot = Robot()
fun click(x: Int, y: Int) {
    val bot = bot
    bot.autoDelay = 15
    bot.mouseMove(x, y)
    bot.mouseMove(x - 5, y)
    bot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
    bot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
}

fun selectColor(color: ARGBColor) {
    val fp = cfg.get().pickerPos

    repeat(4) {
        click(fp.x, fp.y)
        Thread.sleep(10)
    }

    val bot = bot
    bot.autoDelay = 10
    repeat(8) {
        bot.keyPress(KeyEvent.VK_RIGHT)
        bot.keyRelease(KeyEvent.VK_RIGHT)
    }

    val hexStr = String.format(Locale.US, "#%06X", (0xFFFFFF and color.color))
    typeString(hexStr, fp.x, fp.y)
}

fun typeString(str: String, x: Int, y: Int) {
    val bot = bot
    bot.autoDelay = 20
    bot.mouseMove(x, y)
    bot.mouseMove(x - 5, y)
    repeat(str.length) {
        bot.keyPress(KeyEvent.VK_BACK_SPACE)
        bot.keyRelease(KeyEvent.VK_BACK_SPACE)
    }
    for (ch in str) {
        val keyCode = KeyEvent.getExtendedKeyCodeForChar(ch.code)
        if (keyCode != KeyEvent.CHAR_UNDEFINED.code) {
            try {
                bot.keyPress(keyCode)
                bot.keyRelease(keyCode)
            } catch (e: Exception) {
                throw IllegalStateException("Invalid keycode $ch",e)
            }

        }
    }
}