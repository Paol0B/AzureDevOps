package paol0b.azuredevops.toolwindow.review

/** Only the current request may publish data. Clearing or disposal invalidates all tickets. */
class LatestRequest<T> {
    class Ticket<T>(val value: T)
    @Volatile var current: Ticket<T>? = null
        private set
    @Volatile private var disposed = false

    @Synchronized fun start(value: T): Ticket<T>? {
        if (disposed) return null
        return Ticket(value).also { current = it }
    }

    fun isCurrent(ticket: Ticket<T>): Boolean = !disposed && current === ticket

    @Synchronized fun applyIfCurrent(ticket: Ticket<T>, apply: (T) -> Unit): Boolean {
        if (!isCurrent(ticket)) return false
        apply(ticket.value)
        return true
    }

    @Synchronized fun invalidate() { current = null }
    @Synchronized fun dispose() { disposed = true; current = null }
}
