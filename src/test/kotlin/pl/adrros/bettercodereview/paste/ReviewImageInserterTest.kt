package pl.adrros.bettercodereview.paste

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewImageInserterTest {
    @Test
    fun `file names cannot break the markdown link`() {
        assertEquals("![shot_1_ _a_.png](https://x/y)", ReviewImageInserter.imageLink("shot[1] (a).png", "https://x/y"))
    }
}
