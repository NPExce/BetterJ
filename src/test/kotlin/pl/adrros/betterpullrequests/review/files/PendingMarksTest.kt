package pl.adrros.betterpullrequests.review.files

import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PendingMarksTest : BasePlatformTestCase() {
    fun `test threads are counted per file and summed for folders`() {
        val marks = PendingMarks.of("/repo", listOf("src/A.java", "src/A.java", "src/sub/B.java", "README.md"))

        assertEquals(2, marks.countFor(LocalFilePath("/repo/src/A.java", false)))
        assertEquals(1, marks.countFor(LocalFilePath("/repo/src/sub/B.java", false)))
        assertEquals(0, marks.countFor(LocalFilePath("/repo/src/C.java", false)))
        assertEquals(3, marks.sumUnder(LocalFilePath("/repo/src", true)))
        assertEquals(4, marks.sumUnder(LocalFilePath("/repo", true)))
        assertEquals(0, marks.sumUnder(LocalFilePath("/repo/docs", true)))
        assertEquals(3, marks.sumFor(listOf(LocalFilePath("/repo/src/A.java", false), LocalFilePath("/repo/README.md", false), LocalFilePath("/repo/none", false))))
        assertFalse(marks.isEmpty)
        assertTrue(PendingMarks.NONE.isEmpty)
    }
}
