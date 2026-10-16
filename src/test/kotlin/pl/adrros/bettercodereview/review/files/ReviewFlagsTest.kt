package pl.adrros.bettercodereview.review.files

import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ReviewFlagsTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            PropertiesComponent.getInstance(project).unsetValue(ReviewFlags.KEY)
        } finally {
            super.tearDown()
        }
    }

    fun `test flags are per pull request and path, persisted and announced`() {
        val flags = ReviewFlags(project)
        var announced = 0
        flags.addListener { announced++ }
        flags.set("pr-1", "src/A.java")
        assertTrue(flags.isFlagged("pr-1", "src/A.java"))
        assertFalse(flags.isFlagged("pr-2", "src/A.java"))
        assertFalse(flags.isFlagged("pr-1", "src/B.java"))
        flags.set("pr-1", "src/A.java") // already there: no announcement
        flags.clear("pr-1", "src/A.java")
        assertFalse(flags.isFlagged("pr-1", "src/A.java"))
        assertEquals(2, announced)

        flags.set("pr-3", "x/y.kt")
        val reloaded = ReviewFlags(project)
        assertTrue("stored in the project's properties", reloaded.isFlagged("pr-3", "x/y.kt"))
    }
}
