import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/**
 * Windows-only native window styles used to make transparent preview windows
 * mouse-click-through. AWT/Swing has no portable API for this behavior.
 */
internal object WindowsWindowInput {
    private const val GWL_EXSTYLE = -20
    private const val WS_EX_TRANSPARENT = 0x00000020
    private const val WS_EX_LAYERED = 0x00080000

    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020

    private val HWND_TOPMOST: Pointer = Pointer.createConstant(-1L)

    private interface User32 : StdCallLibrary {
        fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Pointer
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Pointer): Pointer
        fun GetWindowLongW(hwnd: Pointer, index: Int): Int
        fun SetWindowLongW(hwnd: Pointer, index: Int, value: Int): Int
        fun SetWindowPos(
            hwnd: Pointer,
            insertAfter: Pointer,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            flags: Int
        ): Boolean
    }

    private val user32: User32 by lazy { Native.load("user32", User32::class.java) }

    fun isSupported(): Boolean = Platform.isWindows()

    /** Throws a useful exception when the native style update cannot be applied. */
    fun setClickThrough(window: Window, enabled: Boolean) {
        check(isSupported()) { "Native click-through is currently supported on Windows only." }
        check(window.isDisplayable) { "The overlay window must be displayable before enabling click-through." }

        val hwnd = Native.getWindowPointer(window)
        val existingStyle = if (Native.POINTER_SIZE >= 8) {
            Pointer.nativeValue(user32.GetWindowLongPtrW(hwnd, GWL_EXSTYLE)).toInt()
        } else {
            user32.GetWindowLongW(hwnd, GWL_EXSTYLE)
        }

        // Layered windows support transparent rendering. WS_EX_TRANSPARENT makes
        // this visual-only window pass hit-testing through to windows behind it.
        var requestedStyle = existingStyle or WS_EX_LAYERED
        requestedStyle = if (enabled) {
            requestedStyle or WS_EX_TRANSPARENT
        } else {
            requestedStyle and WS_EX_TRANSPARENT.inv()
        }

        if (Native.POINTER_SIZE >= 8) {
            user32.SetWindowLongPtrW(
                hwnd,
                GWL_EXSTYLE,
                Pointer.createConstant(requestedStyle.toLong() and 0xFFFF_FFFFL)
            )
        } else {
            user32.SetWindowLongW(hwnd, GWL_EXSTYLE, requestedStyle)
        }

        val refreshed = user32.SetWindowPos(
            hwnd,
            HWND_TOPMOST,
            0,
            0,
            0,
            0,
            SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE or SWP_FRAMECHANGED
        )
        check(refreshed) { "Windows did not accept the overlay window-style update." }
    }
}
