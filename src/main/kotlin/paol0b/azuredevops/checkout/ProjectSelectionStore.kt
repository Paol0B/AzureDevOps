package paol0b.azuredevops.checkout

import com.intellij.ide.util.PropertiesComponent

/**
 * Persists which Azure DevOps projects the user wants to load repos for, keyed by account id.
 *
 * Semantics:
 *  - No stored entry for an account = first visit = treat as "all projects selected".
 *  - Stored entry = exactly those project ids (an empty stored set is a deliberate choice).
 *
 * Stored at application level so the selection survives IDE restarts and is shared across
 * projects (the accounts themselves are application-scoped too).
 */
internal object ProjectSelectionStore {
    private fun key(accountId: String) = "azuredevops.clone.selectedProjects.$accountId"

    fun isStored(accountId: String): Boolean =
        PropertiesComponent.getInstance().isValueSet(key(accountId))

    fun load(accountId: String): Set<String> {
        val raw = PropertiesComponent.getInstance().getValue(key(accountId)) ?: return emptySet()
        if (raw.isEmpty()) return emptySet()
        return raw.split(',').filter { it.isNotBlank() }.toSet()
    }

    fun save(accountId: String, projectIds: Set<String>) {
        PropertiesComponent.getInstance().setValue(key(accountId), projectIds.joinToString(","))
    }

    fun clear(accountId: String) {
        PropertiesComponent.getInstance().unsetValue(key(accountId))
    }
}
