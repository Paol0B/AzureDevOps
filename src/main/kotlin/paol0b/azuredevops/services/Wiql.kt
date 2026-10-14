package paol0b.azuredevops.services

/**
 * Small helpers for building WIQL (Work Item Query Language) fragments.
 * Kept free of IntelliJ/network dependencies so they can be unit-tested.
 */
object Wiql {

    /** Escapes a value for use inside a single-quoted WIQL string literal. */
    fun escape(value: String): String = value.replace("'", "''")

    /**
     * Builds the `[System.State]` condition for the given state names.
     *
     * - empty list → `null` (no condition, every state matches)
     * - one state   → `[System.State] = 'x'` (or `<>` when [exclude] is true)
     * - several     → `[System.State] IN ('x', 'y')` (or `NOT IN`)
     */
    fun stateCondition(states: Collection<String>?, exclude: Boolean = false): String? {
        val names = states.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty()) return null
        if (names.size == 1) {
            val operator = if (exclude) "<>" else "="
            return "[System.State] $operator '${escape(names[0])}'"
        }
        val operator = if (exclude) "NOT IN" else "IN"
        val literals = names.joinToString(", ") { "'${escape(it)}'" }
        return "[System.State] $operator ($literals)"
    }
}
