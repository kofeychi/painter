import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.readText
import kotlin.io.writeText

class SimpleGsonConfigHandler<T> {
    private val clazz: Class<T>
    private val filePath: Path
    private val gson: Gson
    private val defaultSupplier: () -> T
    private var instance: T? = null

    constructor(clazz: Class<T>, filePath: Path, gson: Gson, defaultSupplier: () -> T) {
        this.clazz = clazz
        this.filePath = filePath
        this.gson = gson
        this.defaultSupplier = defaultSupplier

        Runtime.getRuntime().addShutdownHook(Thread {
            save()
        })
    }

    constructor(clazz: Class<T>, filePath: Path, defaultSupplier: () -> T) : this(
            clazz,
            filePath,
            GsonBuilder()
                    .serializeNulls()
                    .setLenient()
                    .setPrettyPrinting()
                    .enableComplexMapKeySerialization()
                    .create(),
            defaultSupplier
    )

    fun get(): T {
        if (instance == null) {
            instance = defaultSupplier.invoke()
            load()
        }
        return instance ?: throw kotlin.IllegalStateException("Instance not initialized")
    }

    fun save() {
        filePath.toFile().writeText(gson.toJson(get(), clazz), StandardCharsets.UTF_8)
    }

    private fun load() {
        if (filePath.toFile().exists()) {
            instance = gson.fromJson(filePath.toFile().readText(StandardCharsets.UTF_8), clazz)
        } else {
            save()
        }
    }
}
