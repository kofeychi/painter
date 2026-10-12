import it.unimi.dsi.fastutil.objects.ObjectArrayList
import org.joml.Vector2i
import java.awt.image.BufferedImage
import java.util.Comparator
import java.util.HashSet
import java.util.LinkedHashMap

data class ARGBColor(
    val color: Int
) : Comparable<ARGBColor?> {
    val alpha: Int
        get() = color shr 24 and 0xFF

    val red: Int
        get() = color shr 16 and 0xFF

    val green: Int
        get() = color shr 8 and 0xFF

    val blue: Int
        get() = color and 0xFF

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        return color == (other as ARGBColor).color
    }

    override fun hashCode(): Int {
        return color
    }

    override fun toString(): String {
        return "ARGBColor(a=$alpha, r=$red, g=$green, b=$blue)"
    }

    override fun compareTo(other: ARGBColor?): Int {
        return Integer.compare(this.color, other?.color ?: 0)
    }

    companion object {
        fun ofTransparent(color: Int): ARGBColor {
            return ARGBColor(color)
        }

        fun ofOpaque(color: Int): ARGBColor {
            return ARGBColor(-0x1000000 or color)
        }

        fun ofRGB(r: Float, g: Float, b: Float): ARGBColor {
            return ofRGBA(r, g, b, 1f)
        }

        fun ofRGB(r: Int, g: Int, b: Int): ARGBColor {
            return ofRGBA(r, g, b, 255)
        }

        fun ofRGBA(r: Float, g: Float, b: Float, a: Float): ARGBColor {
            return ofRGBA(
                (r * 255 + 0.5).toInt(),
                (g * 255 + 0.5).toInt(),
                (b * 255 + 0.5).toInt(),
                (a * 255 + 0.5).toInt()
            )
        }

        fun ofRGBA(r: Int, g: Int, b: Int, a: Int): ARGBColor {
            return ARGBColor(
                ((a and 0xFF) shl 24) or
                        ((r and 0xFF) shl 16) or
                        ((g and 0xFF) shl 8) or
                        (b and 0xFF)
            )
        }
    }
}

data class ExtractionResult(val colors: ObjectArrayList<ARGBColor>)

data class PalletedImage(
    val pallete: MutableMap<ARGBColor, ObjectArrayList<Vector2i>>,
    val size: Vector2i
) {
    companion object {
        fun extractPallete(image: BufferedImage): ExtractionResult {
            val out = ExtractionResult(ObjectArrayList())
            val known = HashSet<ARGBColor>()
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val color = ARGBColor.ofTransparent(image.getRGB(x, y))
                    if (color.alpha < 128) continue
                    if (known.add(color)) out.colors.add(color)
                }
            }
            return out
        }

        fun extractPalletedImage(image: BufferedImage): PalletedImage {
            // JPEGs often contain tens of thousands of distinct colors. Build with a hash map
            // to avoid quadratic lookups while extracting and keep color traversal deterministic.
            val coordsByColor = LinkedHashMap<ARGBColor, ObjectArrayList<Vector2i>>()
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val color = ARGBColor.ofTransparent(image.getRGB(x, y))
                    if (color.alpha < 128) continue
                    coordsByColor.computeIfAbsent(color) { ObjectArrayList() }.add(Vector2i(x, y))
                }
            }
            return PalletedImage(coordsByColor, Vector2i(image.width, image.height))
        }
    }

    fun quantize(targetColorCount: Int): PalletedImage {
        if (targetColorCount <= 0 || pallete.isEmpty() || pallete.size <= targetColorCount) {
            return this
        }

        class ColorCluster(
            val color: ARGBColor,
            val pixelCount: Int,
            val coordinates: ObjectArrayList<Vector2i>
        )

        val clusters = ObjectArrayList<ColorCluster>(pallete.size)
        for ((color, coords) in pallete) {
            clusters.add(ColorCluster(color, coords.size, coords))
        }

        class ColorBox(val boxClusters: ObjectArrayList<ColorCluster>) {
            val totalPixels: Long = boxClusters.sumOf { it.pixelCount.toLong() }

            var minA = 255; var maxA = 0
            var minR = 255; var maxR = 0
            var minG = 255; var maxG = 0
            var minB = 255; var maxB = 0

            init {
                for (cluster in boxClusters) {
                    val c = cluster.color
                    if (c.alpha < minA) minA = c.alpha
                    if (c.alpha > maxA) maxA = c.alpha
                    if (c.red < minR) minR = c.red
                    if (c.red > maxR) maxR = c.red
                    if (c.green < minG) minG = c.green
                    if (c.green > maxG) maxG = c.green
                    if (c.blue < minB) minB = c.blue
                    if (c.blue > maxB) maxB = c.blue
                }
            }

            val weightedRangeA = (maxA - minA) * 1.5f
            val weightedRangeR = (maxR - minR) * 0.299f
            val weightedRangeG = (maxG - minG) * 0.587f
            val weightedRangeB = (maxB - minB) * 0.114f

            val maxWeightedRange = maxOf(weightedRangeA, weightedRangeR, weightedRangeG, weightedRangeB)

            // Priority is weighted by pixel population to prevent rare outlier colors from stealing splits
            val priority: Double = totalPixels.toDouble() * maxWeightedRange.toDouble()

            fun canSplit(): Boolean = boxClusters.size > 1

            fun split(): Pair<ColorBox, ColorBox> {
                when (maxWeightedRange) {
                    weightedRangeA -> boxClusters.sortWith(Comparator.comparingInt { it.color.alpha })
                    weightedRangeR -> boxClusters.sortWith(Comparator.comparingInt { it.color.red })
                    weightedRangeG -> boxClusters.sortWith(Comparator.comparingInt { it.color.green })
                    else           -> boxClusters.sortWith(Comparator.comparingInt { it.color.blue })
                }

                val halfPixels = totalPixels / 2
                var currentPixels = 0L
                var splitIndex = 1

                for (i in 0 until boxClusters.size - 1) {
                    currentPixels += boxClusters[i].pixelCount
                    splitIndex = i + 1
                    if (currentPixels >= halfPixels) break
                }

                val leftList = ObjectArrayList<ColorCluster>()
                val rightList = ObjectArrayList<ColorCluster>()

                for (i in 0 until splitIndex) leftList.add(boxClusters[i])
                for (i in splitIndex until boxClusters.size) rightList.add(boxClusters[i])

                return Pair(ColorBox(leftList), ColorBox(rightList))
            }

            fun computeAverageColor(): ARGBColor {
                if (totalPixels == 0L || boxClusters.isEmpty()) return boxClusters[0].color

                var sumA = 0L
                var sumR = 0L
                var sumG = 0L
                var sumB = 0L

                for (cluster in boxClusters) {
                    val weight = cluster.pixelCount.toLong()
                    val c = cluster.color
                    sumA += c.alpha * weight
                    sumR += c.red * weight
                    sumG += c.green * weight
                    sumB += c.blue * weight
                }

                val avgA = (sumA / totalPixels).toInt().coerceIn(0, 255)
                val avgR = (sumR / totalPixels).toInt().coerceIn(0, 255)
                val avgG = (sumG / totalPixels).toInt().coerceIn(0, 255)
                val avgB = (sumB / totalPixels).toInt().coerceIn(0, 255)

                return ARGBColor.ofRGBA(avgR, avgG, avgB, avgA)
            }
        }

        val boxes = ObjectArrayList<ColorBox>()
        boxes.add(ColorBox(clusters))

        while (boxes.size < targetColorCount) {
            val boxToSplit = boxes
                .filter { it.canSplit() }
                .maxByOrNull { it.priority } ?: break

            boxes.remove(boxToSplit)
            val (left, right) = boxToSplit.split()
            boxes.add(left)
            boxes.add(right)
        }

        val paletteColors = Array(boxes.size) { boxes[it].computeAverageColor() }

        val kMeansIterations = 8
        for (iter in 0 until kMeansIterations) {
            val sumsA = LongArray(paletteColors.size)
            val sumsR = LongArray(paletteColors.size)
            val sumsG = LongArray(paletteColors.size)
            val sumsB = LongArray(paletteColors.size)
            val counts = LongArray(paletteColors.size)

            for (cluster in clusters) {
                val c = cluster.color
                var minDistance = Double.MAX_VALUE
                var closestIndex = 0

                for (pIdx in paletteColors.indices) {
                    val target = paletteColors[pIdx]
                    val dA = (c.alpha - target.alpha).toDouble()
                    val dR = (c.red - target.red).toDouble()
                    val dG = (c.green - target.green).toDouble()
                    val dB = (c.blue - target.blue).toDouble()

                    val dist = dA * dA * 2.0 + dR * dR * 0.299 + dG * dG * 0.587 + dB * dB * 0.114
                    if (dist < minDistance) {
                        minDistance = dist
                        closestIndex = pIdx
                    }
                }

                val w = cluster.pixelCount.toLong()
                sumsA[closestIndex] += c.alpha * w
                sumsR[closestIndex] += c.red * w
                sumsG[closestIndex] += c.green * w
                sumsB[closestIndex] += c.blue * w
                counts[closestIndex] += w
            }

            var changed = false
            for (pIdx in paletteColors.indices) {
                if (counts[pIdx] > 0) {
                    val newA = (sumsA[pIdx] / counts[pIdx]).toInt().coerceIn(0, 255)
                    val newR = (sumsR[pIdx] / counts[pIdx]).toInt().coerceIn(0, 255)
                    val newG = (sumsG[pIdx] / counts[pIdx]).toInt().coerceIn(0, 255)
                    val newB = (sumsB[pIdx] / counts[pIdx]).toInt().coerceIn(0, 255)

                    val newColor = ARGBColor.ofRGBA(newR, newG, newB, newA)
                    if (newColor != paletteColors[pIdx]) {
                        paletteColors[pIdx] = newColor
                        changed = true
                    }
                }
            }
            if (!changed) break
        }

        val newPallete = LinkedHashMap<ARGBColor, ObjectArrayList<Vector2i>>()

        for (cluster in clusters) {
            val c = cluster.color
            var minDistance = Double.MAX_VALUE
            var closestIndex = 0

            for (pIdx in paletteColors.indices) {
                val target = paletteColors[pIdx]
                val dA = (c.alpha - target.alpha).toDouble()
                val dR = (c.red - target.red).toDouble()
                val dG = (c.green - target.green).toDouble()
                val dB = (c.blue - target.blue).toDouble()

                val dist = dA * dA * 2.0 + dR * dR * 0.299 + dG * dG * 0.587 + dB * dB * 0.114
                if (dist < minDistance) {
                    minDistance = dist
                    closestIndex = pIdx
                }
            }

            val finalColor = paletteColors[closestIndex]
            val coordsList = newPallete.computeIfAbsent(finalColor) { ObjectArrayList() }
            coordsList.addAll(cluster.coordinates)
        }

        return PalletedImage(newPallete, size)
    }

    fun toBufferedImage(): BufferedImage {
        val image = BufferedImage(size.x, size.y, BufferedImage.TYPE_INT_ARGB)
        for ((color, coords) in pallete) {
            val argb = color.color
            for (coord in coords) {
                image.setRGB(coord.x, coord.y, argb)
            }
        }
        return image
    }

    fun splitIntoRegions(regionWidth: Int, regionHeight: Int = regionWidth): List<PalletedImage> {
        require(regionWidth > 0 && regionHeight > 0) { "Region dimensions must be positive." }

        val gridWidth = (size.x + regionWidth - 1) / regionWidth
        val gridHeight = (size.y + regionHeight - 1) / regionHeight

        val tilePalettes = Array(gridWidth * gridHeight) {
            LinkedHashMap<ARGBColor, ObjectArrayList<Vector2i>>()
        }

        for ((color, coords) in pallete) {
            for (coord in coords) {
                val tileX = coord.x / regionWidth
                val tileY = coord.y / regionHeight
                val tileIdx = tileY * gridWidth + tileX

                val localX = coord.x % regionWidth
                val localY = coord.y % regionHeight

                val tileMap = tilePalettes[tileIdx]
                val list = tileMap.getOrPut(color) { ObjectArrayList() }
                list.add(Vector2i(localX, localY))
            }
        }

        val regions = ArrayList<PalletedImage>(gridWidth * gridHeight)
        for (tileY in 0 until gridHeight) {
            val currentHeight = minOf(regionHeight, size.y - tileY * regionHeight)
            for (tileX in 0 until gridWidth) {
                val currentWidth = minOf(regionWidth, size.x - tileX * regionWidth)
                val tileIdx = tileY * gridWidth + tileX
                regions.add(PalletedImage(tilePalettes[tileIdx], Vector2i(currentWidth, currentHeight)))
            }
        }

        return regions
    }

    fun splitIntoRegions(regionSize: Vector2i): List<PalletedImage> =
        splitIntoRegions(regionSize.x, regionSize.y)
}