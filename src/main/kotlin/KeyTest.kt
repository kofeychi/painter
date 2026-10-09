import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import kotlin.text.plus

fun main() {
    init {
        val known = mutableListOf<Int>()
        GlobalScreen.addNativeKeyListener(object : NativeKeyListener {
            override fun nativeKeyPressed(nativeEvent: NativeKeyEvent) {
                if(!known.contains(nativeEvent.keyCode)) {
                    println(Char(nativeEvent.keyCode) + " = " + nativeEvent.keyCode)
                    known.add(nativeEvent.keyCode)
                }
            }
        })
    }
}