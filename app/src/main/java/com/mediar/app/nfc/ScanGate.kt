package com.mediar.app.nfc

/**
 * جلوگیری از پردازش چندباره یک تگ وقتی هنوز روی گوشی نگه داشته شده.
 * تا وقتی همان تگ پشت‌سرهم دیده شود، فقط بار اول پردازش می‌شود.
 */
object ScanGate {

    private const val WINDOW_MS = 10_000L

    private var lastId: String? = null
    private var lastAt: Long = 0L

    @Synchronized
    fun shouldHandle(id: String): Boolean {
        val now = System.currentTimeMillis()
        if (id == lastId && now - lastAt < WINDOW_MS) {
            // تا وقتی تگ روی گوشی است، پنجره تمدید می‌شود
            lastAt = now
            return false
        }
        lastId = id
        lastAt = now
        return true
    }
}
