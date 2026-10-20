package paol0b.azuredevops.toolwindow.review

import org.junit.Assert.*
import org.junit.Test

class LatestRequestTest {
    @Test fun `reversed file completion cannot display the old file or change the comment target`() {
        val requests = LatestRequest<String>()
        val first = requests.start("A.kt")!!
        val second = requests.start("B.kt")!!
        var displayed = ""
        assertTrue(requests.applyIfCurrent(second) { displayed = it })
        assertFalse(requests.applyIfCurrent(first) { displayed = it })
        assertEquals("B.kt", displayed)
        assertEquals("B.kt", requests.current!!.value)
    }

    @Test fun `clearing invalidates pending completions and disposal prevents new loads`() {
        val requests = LatestRequest<String>()
        val pending = requests.start("A.kt")!!
        requests.invalidate()
        assertFalse(requests.applyIfCurrent(pending) { fail("Stale completion after clear") })
        val next = requests.start("B.kt")!!
        requests.dispose()
        assertFalse(requests.applyIfCurrent(next) { fail("Completion after disposal") })
        assertNull(requests.start("C.kt"))
    }

    @Test fun `older comment refresh cannot replace newer thread state`() {
        val requests = LatestRequest<List<Int>>()
        val first = requests.start(listOf(1))!!
        val second = requests.start(listOf(1, 2))!!
        var visible = emptyList<Int>()
        requests.applyIfCurrent(second) { visible = it }
        requests.applyIfCurrent(first) { visible = it }
        assertEquals(listOf(1, 2), visible)
    }
}
