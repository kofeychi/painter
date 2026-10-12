import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import com.google.gson.GsonBuilder
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import org.joml.Vector2i
import java.awt.*
import java.awt.geom.Rectangle2D
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.system.exitProcess

private data class LoadedProject(
    val file: File,
    val isVoxel: Boolean,
    val layers: List<PainterLayer>
)

class PainterApplication {
    private val settingsStore = PainterSettingsStore(Paths.get("painter-settings.json"))
    private val state: PainterState = settingsStore.load()
    private val json = GsonBuilder().setLenient().create()

    private var project: LoadedProject? = null
    private var activeSettings: FilePainterSettings? = null
    private var selectedPixel: Vector2i? = null
    private var grid: TransformableGrid? = null
    private var defaultGridCorners: Array<Point>? = null
    private var gridInitialized = false
    private var updatingControls = false

    private val overlay = ScreenOverlay()
    private val points = Points()
    private val controls = JFrame("Palette Painter")
    private val sourceLabel = JLabel("No source selected")
    private val statusLabel = JLabel("Open a PNG, JPEG, or voxel JSON file.")
    private val layerCombo = JComboBox<String>()
    private val paintLayerCheck = JCheckBox("Paint this layer")
    private val outlineCheck = JCheckBox("Red outline preview (2 px inset)")
    private val clickThroughCheck = JCheckBox("Click-through overlay (F8)", true)
    private val pixelLabel = JLabel("Selected pixel: none")
    private val paintPixelCheck = JCheckBox("Paint selected pixel")
    private val chunkWidthSpinner = JSpinner(SpinnerNumberModel(51, 1, 2048, 1))
    private val chunkHeightSpinner = JSpinner(SpinnerNumberModel(51, 1, 2048, 1))
    private val quantizeSpinner = JSpinner(SpinnerNumberModel(16, 0, 256, 1))
    private val imageSettingsPanel = JPanel(GridLayout(0, 2, 8, 5))
    private val previousButton = JButton("← Previous")
    private val nextButton = JButton("Next →")
    private val paintButton = JButton("Paint current layer  (Insert)")

    fun start() {
        buildControls()
        setupOverlay()
        installGlobalHotkeys()
        controls.isVisible = true
        SwingUtilities.invokeLater { chooseAndOpenSource() }
    }

    private fun buildControls() {
        controls.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        controls.isAlwaysOnTop = true
        controls.minimumSize = Dimension(360, 520)
        controls.setSize(400, 650)
        controls.setLocation(24, 80)

        val menu = JMenuBar()
        val fileMenu = JMenu("File")
        fileMenu.add(JMenuItem("Open…").apply { addActionListener { chooseAndOpenSource() } })
        fileMenu.add(JMenuItem("Save settings").apply { addActionListener { saveState(true) } })
        fileMenu.add(JMenuItem("Reset ALL saved states…").apply { addActionListener { resetAllSavedStates() } })
        fileMenu.addSeparator()
        fileMenu.add(JMenuItem("Exit").apply { addActionListener { shutdown() } })
        menu.add(fileMenu)
        controls.jMenuBar = menu

        val root = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = EmptyBorder(12, 12, 12, 12)
            background = UIManager.getColor("Panel.background")
        }

        sourceLabel.font = sourceLabel.font.deriveFont(Font.BOLD, 13f)
        sourceLabel.alignmentX = Component.LEFT_ALIGNMENT
        statusLabel.alignmentX = Component.LEFT_ALIGNMENT
        statusLabel.font = statusLabel.font.deriveFont(11f)

        root.add(section("Source") {
            add(sourceLabel)
            add(Box.createVerticalStrut(6))
            add(JButton("Open image or voxel JSON…").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                addActionListener { chooseAndOpenSource() }
            })
            add(Box.createVerticalStrut(6))
            add(statusLabel)
        })

        root.add(section("Current layer") {
            layerCombo.maximumSize = Dimension(Int.MAX_VALUE, 30)
            add(layerCombo)
            add(Box.createVerticalStrut(6))
            add(paintLayerCheck)
            add(Box.createVerticalStrut(6))
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(previousButton)
                add(Box.createHorizontalStrut(8))
                add(nextButton)
            })
        })

        root.add(section("Pixel selection") {
            add(pixelLabel)
            add(Box.createVerticalStrut(5))
            add(paintPixelCheck)
            add(JLabel("Click a cell on the overlay, then toggle whether it should be painted."))
        })

        imageSettingsPanel.border = null
        imageSettingsPanel.add(JLabel("Chunk width")); imageSettingsPanel.add(chunkWidthSpinner)
        imageSettingsPanel.add(JLabel("Chunk height")); imageSettingsPanel.add(chunkHeightSpinner)
        imageSettingsPanel.add(JLabel("Color limit (0 = original; global for models)")); imageSettingsPanel.add(quantizeSpinner)
        root.add(section("Image / model settings") {
            add(imageSettingsPanel)
        })

        root.add(section("Overlay interaction") {
            add(clickThroughCheck)
            add(JLabel("Click-through passes all overlay mouse input to the app below. Press F8 to temporarily turn it off when you need to drag handles or select pixels."))
            add(Box.createVerticalStrut(5))
            add(JButton("Toggle click-through (F8)").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                addActionListener { setOverlayClickThrough(!overlay.clickThroughEnabled) }
            })
        })

        root.add(section("Preview and actions") {
            add(outlineCheck)
            add(JLabel("Outline mode changes the preview only; painting uses source colors."))
            add(Box.createVerticalStrut(8))
            paintButton.alignmentX = Component.LEFT_ALIGNMENT
            paintButton.addActionListener { startPainting() }
            add(paintButton)
            add(Box.createVerticalStrut(5))
            add(JLabel("Insert: paint current layer   •   Page Down/Up: switch layer"))
            add(JLabel("Delete: exit   •   green handle moves the grid"))
            add(JLabel("Orange X handles adjust the left/right edges; Y handles adjust top/bottom. Corner handles resize proportionally."))
        })

        root.add(Box.createVerticalStrut(6))
        root.add(JButton("Save settings now").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            addActionListener { saveState(true) }
        })
        root.add(Box.createVerticalStrut(6))
        root.add(JButton("Reset ALL saved states…").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            addActionListener { resetAllSavedStates() }
        })

        controls.contentPane = JScrollPane(root).apply {
            verticalScrollBar.unitIncrement = 16
        }

        layerCombo.addActionListener {
            if (!updatingControls) {
                val currentProject = project ?: return@addActionListener
                val settings = activeSettings ?: return@addActionListener
                if (layerCombo.selectedIndex >= 0) {
                    settings.currentLayerIndex = layerCombo.selectedIndex.coerceIn(currentProject.layers.indices)
                    selectedPixel = null
                    refreshControlValues()
                    repaintOverlay()
                    saveState(false)
                }
            }
        }
        paintLayerCheck.addActionListener {
            if (!updatingControls) {
                val currentLayer = activeLayer() ?: return@addActionListener
                val settings = activeSettings ?: return@addActionListener
                settings.layerPaintEnabled[currentLayer.id] = paintLayerCheck.isSelected
                statusLabel.text = if (paintLayerCheck.isSelected) "Layer enabled for painting." else "Layer disabled; painting is skipped."
                repaintOverlay()
                saveState(false)
            }
        }
        outlineCheck.addActionListener {
            if (!updatingControls) {
                activeSettings?.redOutlineOnly = outlineCheck.isSelected
                repaintOverlay()
                saveState(false)
            }
        }
        clickThroughCheck.addActionListener {
            if (!updatingControls) setOverlayClickThrough(clickThroughCheck.isSelected)
        }
        paintPixelCheck.addActionListener {
            if (!updatingControls) {
                val point = selectedPixel ?: return@addActionListener
                val layer = activeLayer() ?: return@addActionListener
                val settings = activeSettings ?: return@addActionListener
                val layerOverrides = settings.pixelPaintEnabled.getOrPut(layer.pixelSettingsId) { mutableMapOf() }
                layerOverrides[pixelKey(layer, point)] = paintPixelCheck.isSelected
                statusLabel.text = if (paintPixelCheck.isSelected) "Selected pixel is enabled." else "Selected pixel will be skipped."
                saveState(false)
                repaintOverlay()
            }
        }
        previousButton.addActionListener { switchLayer(-1) }
        nextButton.addActionListener { switchLayer(1) }

        // Use ordinary listeners so the settings apply immediately after the spinner is committed.
        chunkWidthSpinner.addChangeListener { if (!updatingControls) applyCurrentSourceSettings() }
        chunkHeightSpinner.addChangeListener { if (!updatingControls) applyCurrentSourceSettings() }
        quantizeSpinner.addChangeListener { if (!updatingControls) applyCurrentSourceSettings() }
    }

    private fun section(title: String, content: JPanel.() -> Unit): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(title),
            EmptyBorder(6, 7, 7, 7)
        )
        content()
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height + 4)
    }

    private fun setupOverlay() {
        overlay.frame.title = "Painter overlay"
        // Never send the overlay to the back. Enable click-through by default
        // so mouse input reaches the target drawing app beneath the preview.
        overlay.showOnTop()
        setOverlayClickThrough(true, reportStatus = false)
        val bounds = overlay.frame.bounds
        val center = Vector2i(bounds.width / 2, bounds.height / 2)
        val extent = 256 // Temporary square until a source model supplies its aspect ratio.
        grid = TransformableGrid(
            Rectangle2D.Float(
                (center.x - extent).toFloat(),
                (center.y - extent).toFloat(),
                (extent * 2).toFloat(),
                (extent * 2).toFloat()
            )
        )
        val activeGrid = grid!!
        defaultGridCorners = Array(activeGrid.corners.size) { Point(activeGrid.corners[it]) }
        activeGrid.onTransformChanged = {
            val settings = activeSettings
            if (settings != null) {
                settings.gridCornerX = activeGrid.corners.map { it.x }.toMutableList()
                settings.gridCornerY = activeGrid.corners.map { it.y }.toMutableList()
                saveState(false)
            }
        }
        activeGrid.onCellClicked = { pixel ->
            selectedPixel = pixel
            refreshSelectedPixelControl()
            repaintOverlay()
        }
        val gridMouseAdapter = activeGrid.adapter(overlay)
        overlay.drawPanel.addMouseListener(gridMouseAdapter)
        overlay.drawPanel.addMouseMotionListener(gridMouseAdapter)

        val savedPicker = Point(state.pickerX, state.pickerY)
        points.points[0] = ColoredPoint(ARGBColor.ofRGB(255, 0, 0), savedPicker)
        points.onSet = { index, coloredPoint ->
            if (index == 0) {
                state.pickerX = coloredPoint.point.x
                state.pickerY = coloredPoint.point.y
            }
        }
        points.onRelease = { saveState(false) }
        val pointAdapter = points.adapter(overlay)
        overlay.drawPanel.addMouseListener(pointAdapter)
        overlay.drawPanel.addMouseMotionListener(pointAdapter)

        overlay.drawPanel.on = { graphics ->
            val activeProject = project
            val activeLayer = activeLayer()
            if (activeProject != null && activeLayer != null) {
                val settings = activeSettings
                val layerEnabled = settings?.layerPaintEnabled?.getOrDefault(activeLayer.id, true) ?: true
                activeGrid.paint(
                    graphics,
                    activeLayer.image,
                    shouldDrawImage = true,
                    redOutlineOnly = settings?.redOutlineOnly ?: false,
                    isPixelEnabled = { coordinate -> isPixelEnabled(activeLayer, coordinate) && layerEnabled },
                    selectedPixel = selectedPixel
                )
            }
            points.draw(graphics)
        }
        gridInitialized = true
        repaintOverlay()
    }

    private fun chooseAndOpenSource() {
        val chooser = JFileChooser().apply {
            currentDirectory = (project?.file?.parentFile ?: Paths.get(".").toAbsolutePath().toFile())
            fileFilter = FileNameExtensionFilter("Images and voxel models (PNG, JPG, JPEG, JSON)", "png", "jpg", "jpeg", "json")
            isAcceptAllFileFilterUsed = true
        }
        if (chooser.showOpenDialog(controls) != JFileChooser.APPROVE_OPTION) return
        try {
            openProject(chooser.selectedFile)
        } catch (exception: Exception) {
            JOptionPane.showMessageDialog(
                controls,
                "Could not open file:\n${exception.message ?: exception.javaClass.simpleName}",
                "Open failed",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    private fun openProject(file: File) {
        val normalizedFile = file.toPath().toAbsolutePath().normalize().toFile()
        val key = normalizedFile.path
        val settings = state.files.getOrPut(key) { FilePainterSettings() }
        val loadedProject = loadProject(normalizedFile, settings)
        project = loadedProject
        activeSettings = settings
        settings.currentLayerIndex = settings.currentLayerIndex.coerceIn(loadedProject.layers.indices)
        selectedPixel = null
        sourceLabel.text = "${normalizedFile.name}  (${if (project!!.isVoxel) "voxel JSON" else "image"})"
        statusLabel.text = "${project!!.layers.size} selectable layer(s). Click a cell to edit its paint state."
        applyFileGridSettings()
        setupCurrentLayerGrid()
        refreshControlValues()
        saveState(false)
    }

    private fun loadProject(file: File, settings: FilePainterSettings): LoadedProject {
        val extension = file.extension.lowercase(Locale.ROOT)
        if (extension == "json") {
            val model = json.fromJson(Files.readString(file.toPath()), VoxelModel::class.java)
                ?: throw IllegalArgumentException("The JSON file is empty or is not a voxel model.")
            settings.quantizeColors = settings.quantizeColors.coerceIn(0, 256)
            val layers = VoxelParser.parse(model, settings.quantizeColors)
            return LoadedProject(file, true, layers)
        }
        if (extension !in setOf("png", "jpg", "jpeg")) {
            throw IllegalArgumentException("Supported file types are PNG, JPG, JPEG, and voxel JSON.")
        }
        val bufferedImage = ImageIO.read(file)
            ?: throw IllegalArgumentException("The selected file could not be decoded as an image.")
        settings.quantizeColors = settings.quantizeColors.coerceIn(0, 256)
        var palleted = PalletedImage.extractPalletedImage(bufferedImage)
        if (settings.quantizeColors > 0) palleted = palleted.quantize(settings.quantizeColors)
        val chunkWidth = settings.chunkWidth.coerceIn(1, 2048)
        val chunkHeight = settings.chunkHeight.coerceIn(1, 2048)
        settings.chunkWidth = chunkWidth
        settings.chunkHeight = chunkHeight
        val chunks = palleted.splitIntoRegions(chunkWidth, chunkHeight)
        val chunkColumns = (palleted.size.x + chunkWidth - 1) / chunkWidth
        val layers = chunks.mapIndexed { index, chunk ->
            val chunkX = index % chunkColumns
            val chunkY = index / chunkColumns
            sortCoordinates(chunk)
            PainterLayer(
                id = "chunk:$chunkX:$chunkY",
                label = "Chunk ${index + 1}/${chunks.size}  (col $chunkX, row $chunkY)",
                image = chunk,
                originX = chunkX * chunkWidth,
                originY = chunkY * chunkHeight,
                pixelSettingsId = "image-pixels"
            )
        }
        return LoadedProject(file, false, layers)
    }

    private fun sortCoordinates(image: PalletedImage) {
        val width = image.size.x.coerceAtLeast(1)
        val comparator = compareBy<Vector2i> { it.y * width + it.x }
        for ((color, coords) in image.pallete) {
            image.pallete[color] = ObjectArrayList(coords.sortedWith(comparator))
        }
    }

    private fun defaultGridCornersForLayer(size: Vector2i): Array<Point> {
        val width = size.x.coerceAtLeast(1)
        val height = size.y.coerceAtLeast(1)
        // Keep the layer's exact width:height ratio while using the same 512 px
        // maximum preview dimension as the former default square overlay.
        val ratioScale = 512.0 / maxOf(width, height).toDouble()
        val previewWidth = (width * ratioScale).toInt().coerceAtLeast(1)
        val previewHeight = (height * ratioScale).toInt().coerceAtLeast(1)
        val bounds = overlay.frame.bounds
        val centerX = bounds.width / 2
        val centerY = bounds.height / 2
        val left = centerX - previewWidth / 2
        val top = centerY - previewHeight / 2
        return arrayOf(
            Point(left, top),
            Point(left + previewWidth, top),
            Point(left + previewWidth, top + previewHeight),
            Point(left, top + previewHeight)
        )
    }

    private fun applyFileGridSettings() {
        val activeGrid = grid ?: return
        val settings = activeSettings
        val sourceSize = activeLayer()?.image?.size ?: activeGrid.grid_dimensions
        val defaults = defaultGridCornersForLayer(sourceSize)
        // Keep reset-to-default geometry specific to the loaded source/layer.
        defaultGridCorners = Array(defaults.size) { Point(defaults[it]) }

        val hasSavedCorners = settings != null && settings.gridCornerX.size == 4 && settings.gridCornerY.size == 4
        // Preserve an explicitly saved alignment. Files without saved geometry (including
        // older settings that never stored a user transform) receive the model's aspect ratio.
        if (hasSavedCorners && settings != null) {
            for (index in activeGrid.corners.indices) {
                activeGrid.corners[index].setLocation(settings.gridCornerX[index], settings.gridCornerY[index])
            }
        } else {
            for (index in activeGrid.corners.indices) activeGrid.corners[index].location = Point(defaults[index])
        }
        activeGrid.recalculatePoints()
    }

    private fun setupCurrentLayerGrid() {
        val currentLayer = activeLayer() ?: return
        grid?.recalc(currentLayer.image.size)
        progressHUD.updateSourceLayer((activeSettings?.currentLayerIndex ?: 0) + 1, project?.layers?.size ?: 1)
        repaintOverlay()
    }

    private fun rebuildCurrentProject() {
        val current = project ?: return
        val settings = activeSettings ?: return
        try {
            settings.currentLayerIndex = settings.currentLayerIndex.coerceAtLeast(0)
            project = loadProject(current.file, settings)
            settings.currentLayerIndex = settings.currentLayerIndex.coerceIn(project!!.layers.indices)
            selectedPixel = null
            refreshControlValues()
            setupCurrentLayerGrid()
            statusLabel.text = "Color and source settings applied."
            saveState(false)
        } catch (exception: Exception) {
            JOptionPane.showMessageDialog(controls, "Could not rebuild image: ${exception.message}", "Settings error", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun applyCurrentSourceSettings() {
        if (updatingControls) return
        val activeProject = project ?: return
        val settings = activeSettings ?: return
        settings.quantizeColors = (quantizeSpinner.value as Number).toInt().coerceIn(0, 256)
        if (!activeProject.isVoxel) {
            settings.chunkWidth = (chunkWidthSpinner.value as Number).toInt()
            settings.chunkHeight = (chunkHeightSpinner.value as Number).toInt()
        }
        rebuildCurrentProject()
    }

    private fun refreshControlValues() {
        val activeProject = project
        val settings = activeSettings
        updatingControls = true
        try {
            layerCombo.removeAllItems()
            if (activeProject != null) {
                activeProject.layers.forEach { layerCombo.addItem(it.label) }
                if (activeProject.layers.isNotEmpty()) {
                    layerCombo.selectedIndex = settings?.currentLayerIndex?.coerceIn(activeProject.layers.indices) ?: 0
                }
            }
            val layer = activeLayer()
            paintLayerCheck.isEnabled = layer != null
            paintLayerCheck.isSelected = layer != null && (settings?.layerPaintEnabled?.getOrDefault(layer.id, true) ?: true)
            outlineCheck.isSelected = settings?.redOutlineOnly ?: false
            imageSettingsPanel.isEnabled = activeProject != null
            val rasterActive = activeProject != null && !activeProject.isVoxel
            imageSettingsPanel.components.forEachIndexed { index, component ->
                // Components 0-3 are the two raster chunk labels/spinners. The
                // final label/spinner (4-5) control quantization for images and models.
                component.isEnabled = if (index in 0..3) rasterActive else activeProject != null
            }
            chunkWidthSpinner.value = settings?.chunkWidth?.coerceAtLeast(1) ?: 51
            chunkHeightSpinner.value = settings?.chunkHeight?.coerceAtLeast(1) ?: 51
            quantizeSpinner.value = settings?.quantizeColors?.coerceIn(0, 256) ?: 16
            refreshSelectedPixelControl()
            paintButton.isEnabled = activeProject != null && layer != null && !PaintAutomation.running
            previousButton.isEnabled = activeProject != null && activeProject.layers.size > 1
            nextButton.isEnabled = activeProject != null && activeProject.layers.size > 1
        } finally {
            updatingControls = false
        }
    }

    private fun refreshSelectedPixelControl() {
        val layer = activeLayer()
        val pixel = selectedPixel
        updatingControls = true
        try {
            if (pixel == null || layer == null) {
                pixelLabel.text = "Selected pixel: none"
                paintPixelCheck.isSelected = false
                paintPixelCheck.isEnabled = false
            } else {
                val enabled = isPixelEnabled(layer, pixel)
                val sourceX = pixel.x + layer.originX
                val sourceY = pixel.y + layer.originY
                val coords = if (project?.isVoxel == true) "x=${pixel.x}, z=${pixel.y}" else "x=$sourceX, y=$sourceY"
                pixelLabel.text = "Selected pixel: $coords ${if (enabled) "(enabled)" else "(skipped)"}"
                paintPixelCheck.isSelected = enabled
                paintPixelCheck.isEnabled = true
            }
        } finally {
            updatingControls = false
        }
    }

    private fun switchLayer(delta: Int) {
        val activeProject = project ?: return
        val settings = activeSettings ?: return
        if (activeProject.layers.isEmpty()) return
        settings.currentLayerIndex = (settings.currentLayerIndex + delta).mod(activeProject.layers.size)
        selectedPixel = null
        refreshControlValues()
        setupCurrentLayerGrid()
        saveState(false)
    }

    private fun activeLayer(): PainterLayer? {
        val activeProject = project ?: return null
        if (activeProject.layers.isEmpty()) return null
        val index = (activeSettings?.currentLayerIndex ?: 0).coerceIn(activeProject.layers.indices)
        return activeProject.layers[index]
    }

    private fun pixelKey(layer: PainterLayer, pixel: Vector2i): String =
        "${pixel.x + layer.originX},${pixel.y + layer.originY}"

    private fun isPixelEnabled(layer: PainterLayer, pixel: Vector2i): Boolean {
        val settings = activeSettings ?: return true
        return settings.pixelPaintEnabled[layer.pixelSettingsId]?.getOrDefault(pixelKey(layer, pixel), true) ?: true
    }

    private fun repaintOverlay() {
        if (gridInitialized) {
            overlay.drawPanel.repaint()
            overlay.frame.repaint()
        }
    }

    private fun startPainting() {
        if (PaintAutomation.running) return
        val layer = activeLayer() ?: run {
            statusLabel.text = "Open an image or voxel JSON file first."
            return
        }
        val settings = activeSettings ?: return
        if (!settings.layerPaintEnabled.getOrDefault(layer.id, true)) {
            statusLabel.text = "This layer is disabled. Enable ‘Paint this layer’ first."
            return
        }
        val activeGrid = grid ?: return
        activeGrid.recalc(layer.image.size)
        val enabledPixels: (Vector2i) -> Boolean = { isPixelEnabled(layer, it) }
        val total = layer.image.pallete.entries.sumOf { (color, coords) ->
            if (color.alpha < 128) 0 else coords.count(enabledPixels)
        }
        if (total == 0) {
            statusLabel.text = "There are no enabled opaque pixels in this layer."
            return
        }
        if (!PaintAutomation.reserve()) return
        statusLabel.text = "Painting ${layer.label}…"
        refreshControlValues()
        try {
            Thread {
                PaintAutomation.autoPaint(
                    grid = activeGrid,
                    palletes = layer.image,
                    isPixelEnabled = enabledPixels,
                    picker = { Point(state.pickerX, state.pickerY) },
                    hider = { visible ->
                        val updateUi = {
                            overlay.isVisible = visible
                            if (visible) {
                                overlay.frame.repaint()
                                statusLabel.text = "Ready."
                                refreshControlValues()
                            }
                        }
                        if (!visible && !SwingUtilities.isEventDispatchThread()) {
                            SwingUtilities.invokeAndWait(updateUi)
                        } else if (SwingUtilities.isEventDispatchThread()) {
                            updateUi()
                        } else {
                            SwingUtilities.invokeLater(updateUi)
                        }
                    }
                )
            }.apply { name = "auto-paint-${layer.id}"; isDaemon = true; start() }
        } catch (exception: Exception) {
            PaintAutomation.cancelReservation()
            statusLabel.text = "Could not start painting: ${exception.message}"
            refreshControlValues()
        }
    }

    private fun resetAllSavedStates() {
        val answer = JOptionPane.showConfirmDialog(
            controls,
            "Delete saved settings for every source file and reset the overlay position, picker, layers, pixel overrides, and image options?\nThis cannot be undone.",
            "Reset all saved states",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (answer != JOptionPane.YES_OPTION) return

        val currentFile = project?.file
        state.files.clear()
        state.pickerX = 10
        state.pickerY = 10
        activeSettings = currentFile?.let { file ->
            FilePainterSettings().also { fresh ->
                fresh.currentLayerIndex = 0
                state.files[file.toPath().toAbsolutePath().normalize().toString()] = fresh
            }
        }
        points.points[0] = ColoredPoint(ARGBColor.ofRGB(255, 0, 0), Point(state.pickerX, state.pickerY))

        if (currentFile != null && activeSettings != null) {
            try {
                project = loadProject(currentFile, activeSettings!!)
                activeSettings!!.currentLayerIndex = 0
                applyFileGridSettings() // Rebuilds the default overlay using the source's aspect ratio.
                setupCurrentLayerGrid()
            } catch (exception: Exception) {
                statusLabel.text = "States reset, but source reload failed: ${exception.message}"
            }
        } else {
            val defaults = defaultGridCornersForLayer(Vector2i(1, 1))
            defaultGridCorners = Array(defaults.size) { Point(defaults[it]) }
            grid?.let { activeGrid ->
                for (index in activeGrid.corners.indices) activeGrid.corners[index].location = Point(defaults[index])
                activeGrid.recalculatePoints()
            }
        }
        refreshControlValues()
        repaintOverlay()
        saveState(false)
        statusLabel.text = "All saved states reset to defaults."
    }

    private fun saveState(showMessage: Boolean) {
        val currentProject = project
        val settings = activeSettings
        if (currentProject != null && settings != null) {
            state.files[currentProject.file.toPath().toAbsolutePath().normalize().toString()] = settings
        }
        try {
            settingsStore.save(state)
            if (showMessage) statusLabel.text = "Settings saved to painter-settings.json"
        } catch (exception: Exception) {
            if (showMessage) JOptionPane.showMessageDialog(
                controls,
                "Could not save settings: ${exception.message}",
                "Save failed",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    private fun setOverlayClickThrough(enabled: Boolean, reportStatus: Boolean = true) {
        val applied = overlay.setClickThrough(enabled)
        updatingControls = true
        try {
            clickThroughCheck.isSelected = overlay.clickThroughEnabled
        } finally {
            updatingControls = false
        }

        if (applied) {
            if (reportStatus) {
                statusLabel.text = if (enabled) {
                    "Overlay is click-through. Press F8 to interact with it."
                } else {
                    "Overlay interaction enabled. Press F8 to pass clicks through."
                }
            }
        } else {
            statusLabel.text = "Could not enable click-through: ${overlay.clickThroughError ?: "unsupported platform"}"
        }
    }

    private fun installGlobalHotkeys() {
        val listener = object : NativeKeyListener {
            override fun nativeKeyPressed(nativeEvent: NativeKeyEvent) {
                when (nativeEvent.keyCode) {
                    NativeKeyEvent.VC_DELETE -> SwingUtilities.invokeLater { shutdown() }
                    NativeKeyEvent.VC_INSERT -> SwingUtilities.invokeLater { startPainting() }
                    NativeKeyEvent.VC_PAGE_DOWN -> SwingUtilities.invokeLater { switchLayer(1) }
                    NativeKeyEvent.VC_PAGE_UP -> SwingUtilities.invokeLater { switchLayer(-1) }
                    NativeKeyEvent.VC_F8 -> SwingUtilities.invokeLater {
                        setOverlayClickThrough(!overlay.clickThroughEnabled)
                    }
                }
            }
            override fun nativeKeyReleased(nativeEvent: NativeKeyEvent) = Unit
            override fun nativeKeyTyped(nativeEvent: NativeKeyEvent) = Unit
        }
        Thread {
            try {
                if (!GlobalScreen.isNativeHookRegistered()) GlobalScreen.registerNativeHook()
                GlobalScreen.addNativeKeyListener(listener)
            } catch (exception: Exception) {
                System.err.println("Could not register global hotkeys: ${exception.message}")
                SwingUtilities.invokeLater {
                    statusLabel.text = "Global hotkeys unavailable; use the controls window instead."
                }
            }
        }.apply { name = "global-hotkeys"; isDaemon = true; start() }
    }

    private fun shutdown() {
        saveState(false)
        try {
            if (GlobalScreen.isNativeHookRegistered()) GlobalScreen.unregisterNativeHook()
        } catch (_: Exception) {
        }
        progressHUD.stopOverlay()
        overlay.frame.dispose()
        controls.dispose()
        exitProcess(0)
    }
}
