import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Settings are keyed by the normalized absolute source-file path, so each file keeps its own setup. */
class PainterState {
    var pickerX: Int = 10
    var pickerY: Int = 10
    var files: MutableMap<String, FilePainterSettings> = mutableMapOf()
}

class FilePainterSettings {
    var currentLayerIndex: Int = 0
    var quantizeColors: Int = 16
    var chunkWidth: Int = 51
    var chunkHeight: Int = 51
    var redOutlineOnly: Boolean = false
    var gridCornerX: MutableList<Int> = mutableListOf()
    var gridCornerY: MutableList<Int> = mutableListOf()
    var layerPaintEnabled: MutableMap<String, Boolean> = mutableMapOf()
    /** Missing entries mean enabled; values override that default for an individual source pixel. */
    var pixelPaintEnabled: MutableMap<String, MutableMap<String, Boolean>> = mutableMapOf()
}

class PainterSettingsStore(private val file: Path) {
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .create()

    fun load(): PainterState {
        if (!Files.exists(file)) return PainterState()
        return try {
            gson.fromJson(Files.readString(file), PainterState::class.java) ?: PainterState()
        } catch (exception: Exception) {
            System.err.println("Could not read ${file.toAbsolutePath()}: ${exception.message}")
            PainterState()
        }
    }

    fun save(state: PainterState) {
        val parent = file.toAbsolutePath().parent
        if (parent != null) Files.createDirectories(parent)
        val temporary = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(temporary, gson.toJson(state))
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
