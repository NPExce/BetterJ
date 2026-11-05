package pl.adrros.bettercodereview.telemetry

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.time.LocalDate

class UsagePingTest : BasePlatformTestCase() {
    fun `test the ping holds the install id and the two versions, nothing else`() {
        assertEquals(
            """{"product":"betterj","id":"3f2b1c4d-1111-4abc-9def-0123456789ab","version":"0.21.2","ide":"IU-261.22158.277"}""",
            UsagePing.body("3f2b1c4d-1111-4abc-9def-0123456789ab", "0.21.2", "IU-261.22158.277"),
        )
    }

    fun `test nothing is sent from tests or before the service exists`() {
        assertNull(UsagePing.dueBody(LocalDate.of(2026, 11, 5)))
    }
}
