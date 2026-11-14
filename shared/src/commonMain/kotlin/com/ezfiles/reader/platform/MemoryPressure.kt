package com.ezfiles.reader.platform

/**
 * Lets caches give memory back when the system asks. The platform host calls
 * [trim] when the app's UI goes to the background or memory runs low (Android:
 * `onTrimMemory`), so a cached page or picture does not get the whole app
 * killed while it sits unused. Main thread only, like the caches it serves.
 */
object MemoryPressure {
    private val listeners = LinkedHashSet<() -> Unit>()

    /** Adds [onTrim]; call the returned function to remove it again. */
    fun register(onTrim: () -> Unit): () -> Unit {
        listeners += onTrim
        return { listeners -= onTrim }
    }

    fun trim() {
        // A copy: a listener may unregister itself while running.
        listeners.toList().forEach { it() }
    }
}
