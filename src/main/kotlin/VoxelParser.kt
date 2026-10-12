import it.unimi.dsi.fastutil.objects.ObjectArrayList
import org.joml.Vector2i

/** One selectable image plane in the painter. */
data class PainterLayer(
    val id: String,
    val label: String,
    val image: PalletedImage,
    val originX: Int = 0,
    val originY: Int = 0,
    val pixelSettingsId: String = id
)

/** Converts the legacy voxel JSON format into one x/z palletized image per y layer. */
object VoxelParser {
    fun parse(model: VoxelModel, targetColorCount: Int = 0): List<PainterLayer> {
        val declaredDimensions = model.dimension.firstOrNull()
            ?: throw IllegalArgumentException("Voxel JSON has no 'dimension' entry.")
        require(declaredDimensions.width > 0 && declaredDimensions.height > 0 && declaredDimensions.depth > 0) {
            "Voxel dimensions must all be greater than zero."
        }

        // Some exporters serialize dimension.width/height/depth as the largest valid
        // coordinate instead of the number of cells. Derive the required extents from
        // the voxel coordinates as well, so a voxel on the declared upper boundary is
        // included instead of being silently discarded (for example y = 50 when
        // dimension.height = 50). Never shrink the declared dimensions.
        fun extentAtLeast(declared: Int, maximumCoordinate: Int, axis: String): Int {
            require(maximumCoordinate < Int.MAX_VALUE) {
                "Voxel $axis coordinate is too large to represent as a dimension."
            }
            return maxOf(declared, maximumCoordinate + 1)
        }

        val nonNegativeVoxels = model.voxels.filter { it.x >= 0 && it.y >= 0 && it.z >= 0 }
        val width = extentAtLeast(declaredDimensions.width, nonNegativeVoxels.maxOfOrNull { it.x } ?: -1, "X")
        val height = extentAtLeast(declaredDimensions.height, nonNegativeVoxels.maxOfOrNull { it.y } ?: -1, "Y")
        val depth = extentAtLeast(declaredDimensions.depth, nonNegativeVoxels.maxOfOrNull { it.z } ?: -1, "Z")
        val paletteByLayer = Array(height) {
            LinkedHashMap<ARGBColor, ObjectArrayList<Vector2i>>()
        }

        for (voxel in model.voxels) {
            if (voxel.x !in 0 until width ||
                voxel.y !in 0 until height ||
                voxel.z !in 0 until depth
            ) {
                // Exporters occasionally leave invalid entries behind; they cannot be painted.
                continue
            }

            val color = ARGBColor.ofRGB(
                voxel.red.coerceIn(0, 255),
                voxel.green.coerceIn(0, 255),
                voxel.blue.coerceIn(0, 255)
            )
            val palette = paletteByLayer[voxel.y]
            palette.computeIfAbsent(color) { ObjectArrayList() }
                .add(Vector2i(voxel.x, voxel.z))
        }

        // Quantize all voxel layers together, not each layer independently. This keeps
        // the same source colors mapped to the same quantized palette color throughout
        // the model and makes the configured limit a model-wide color limit.
        if (targetColorCount > 0) {
            val stackedDepthLong = depth.toLong() * height.toLong()
            require(stackedDepthLong in 1..Int.MAX_VALUE.toLong()) {
                "Voxel model is too tall to quantize as a single palette."
            }
            val stackedDepth = stackedDepthLong.toInt()
            val stackedPalette = LinkedHashMap<ARGBColor, ObjectArrayList<Vector2i>>()
            for (layerY in paletteByLayer.indices) {
                for ((color, coords) in paletteByLayer[layerY]) {
                    val targetCoords = stackedPalette.computeIfAbsent(color) { ObjectArrayList() }
                    for (coord in coords) {
                        targetCoords.add(Vector2i(coord.x, layerY * depth + coord.y))
                    }
                }
            }

            val quantized = PalletedImage(
                stackedPalette,
                Vector2i(width, stackedDepth)
            ).quantize(targetColorCount)

            for (palette in paletteByLayer) palette.clear()
            for ((color, coords) in quantized.pallete) {
                for (coord in coords) {
                    val layerY = coord.y / depth
                    val localZ = coord.y % depth
                    if (layerY in paletteByLayer.indices) {
                        paletteByLayer[layerY].computeIfAbsent(color) { ObjectArrayList() }
                            .add(Vector2i(coord.x, localZ))
                    }
                }
            }
        }

        return (0 until height).map { y ->
            val palette = paletteByLayer[y]
            sortCoordinates(palette, width)
            PainterLayer(
                id = "voxel-y:$y",
                label = "Y = $y  (${palette.values.sumOf { it.size }} voxels)",
                image = PalletedImage(palette, Vector2i(width, depth))
            )
        }
    }

    private fun sortCoordinates(
        palette: MutableMap<ARGBColor, ObjectArrayList<Vector2i>>,
        width: Int
    ) {
        val comparator = compareBy<Vector2i> { it.y * width + it.x }
        for ((color, coordinates) in palette) {
            palette[color] = ObjectArrayList(coordinates.sortedWith(comparator))
        }
    }
}
