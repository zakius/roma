package roma.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionTest {
    @Test fun newer() {
        assertTrue(newerVersion("0.1.10", "0.1.9"))
        assertTrue(newerVersion("0.2", "0.1.46"))
        assertTrue(newerVersion("0.1.46.1", "0.1.46"))
        assertFalse(newerVersion("0.1.46", "0.1.46"))
        assertFalse(newerVersion("0.1.45", "0.1.46"))
        assertFalse(newerVersion("0.1.46", "0.1.46.0"))
    }
}
