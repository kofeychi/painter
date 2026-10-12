import java.awt.*
import java.text.NumberFormat
import java.util.Locale
import javax.swing.*
import javax.swing.border.EmptyBorder

class PaintProgressOverlay : JWindow() {
    private val numberFormat = NumberFormat.getIntegerInstance(Locale.US)
    private val titleLabel = JLabel("AutoPaint Active")
    private val layerLabel = JLabel("Source layer: 1 / 1")
    private val elapsedLabel = JLabel("Elapsed: 00:00")
    private val remainingLabel = JLabel("Remaining: Calculating...")
    private val paletteLayerLabel = JLabel("Palette layer: 0 / 0")
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
        contentPane = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Color(20, 24, 30, 230)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(60, 130, 240, 180), 1),
                EmptyBorder(10, 14, 10, 14)
            )
            fun style(label: JLabel, size: Float, bold: Boolean = false, color: Color = Color.WHITE) {
                label.alignmentX = Component.LEFT_ALIGNMENT
                label.foreground = color
                label.font = label.font.deriveFont(if (bold) Font.BOLD else Font.PLAIN, size)
            }
            style(titleLabel, 14f, true, Color(100, 200, 255))
            style(layerLabel, 13f, true, Color(150, 220, 150))
            style(elapsedLabel, 12f, false, Color(220, 220, 220))
            style(remainingLabel, 13f, true, Color(255, 200, 80))
            style(paletteLayerLabel, 11f, false, Color(180, 180, 180))
            style(countLabel, 11f, false, Color(180, 180, 180))
            progressBar.apply {
                alignmentX = Component.LEFT_ALIGNMENT
                isStringPainted = false
                preferredSize = Dimension(220, 8)
                maximumSize = Dimension(240, 8)
                foreground = Color(60, 160, 240)
                background = Color(40, 45, 55)
                border = BorderFactory.createEmptyBorder()
            }
            add(titleLabel)
            add(layerLabel)
            add(Box.createVerticalStrut(6))
            add(elapsedLabel)
            add(remainingLabel)
            add(Box.createVerticalStrut(6))
            add(progressBar)
            add(Box.createVerticalStrut(4))
            add(paletteLayerLabel)
            add(countLabel)
        }
        pack()
        setLocation(20, 20)
    }

    fun updateSourceLayer(current: Int, total: Int) {
        SwingUtilities.invokeLater {
            layerLabel.text = "Source layer: $current / $total"
            pack()
        }
    }

    fun start(totalPx: Int, layersCount: Int) {
        SwingUtilities.invokeLater {
            totalPixels = totalPx
            completedPixels = 0
            totalLayers = layersCount
            currentLayer = 0
            startTimeMs = System.currentTimeMillis()
            progressBar.value = 0
            remainingLabel.text = "Remaining: Calculating..."
            updateDisplay()
            updateTimer?.stop()
            updateTimer = Timer(200) { updateDisplay() }.also { it.start() }
            isVisible = true
        }
    }

    fun setProgress(completedPx: Int, layerIdx: Int) {
        completedPixels = completedPx
        currentLayer = layerIdx
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
            val remainingMs = ((totalPixels - completedPixels) * msPerPixel).toLong()
            remainingLabel.text = "Remaining: ${formatDuration(remainingMs)}"
            val pct = completedPixels.toDouble() / totalPixels.toDouble() * 100.0
            progressBar.value = pct.toInt().coerceIn(0, 100)
            countLabel.text = "${numberFormat.format(completedPixels)} / ${numberFormat.format(totalPixels)} (${String.format(Locale.US, "%.1f", pct)}%)"
        } else {
            remainingLabel.text = "Remaining: Calculating..."
            progressBar.value = 0
            countLabel.text = "0 / ${numberFormat.format(totalPixels)} (0.0%)"
        }
        paletteLayerLabel.text = "Palette layer: $currentLayer / $totalLayers"
        pack()
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSec = ms / 1000
        val seconds = totalSec % 60
        val minutes = (totalSec / 60) % 60
        val hours = totalSec / 3600
        return if (hours > 0) String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

internal val progressHUD = PaintProgressOverlay()
