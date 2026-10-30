package paol0b.azuredevops.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WiqlTest {

    @Test
    fun `no states means no condition`() {
        assertNull(Wiql.stateCondition(null))
        assertNull(Wiql.stateCondition(emptyList()))
        assertNull(Wiql.stateCondition(listOf("", "  ")))
    }

    @Test
    fun `single state uses equality`() {
        assertEquals("[System.State] = 'WORK IN PROGRESS'", Wiql.stateCondition(listOf("WORK IN PROGRESS")))
        assertEquals("[System.State] <> 'Closed'", Wiql.stateCondition(listOf("Closed"), exclude = true))
    }

    @Test
    fun `several states use IN and NOT IN`() {
        assertEquals(
            "[System.State] IN ('TODO', 'FIX NEEDED')",
            Wiql.stateCondition(listOf("TODO", "FIX NEEDED"))
        )
        assertEquals(
            "[System.State] NOT IN ('Closed', 'Removed')",
            Wiql.stateCondition(listOf("Closed", "Removed"), exclude = true)
        )
    }

    @Test
    fun `quotes are escaped and duplicates removed`() {
        assertEquals("[System.State] = 'Won''t Fix'", Wiql.stateCondition(listOf("Won't Fix", "Won't Fix")))
    }
}
