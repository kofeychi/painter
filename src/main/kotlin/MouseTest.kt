import java.awt.MouseInfo
import java.lang.Thread.sleep

fun main() {
    sleep(1000)
    println(MouseInfo.getPointerInfo().location)
}