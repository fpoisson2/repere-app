package ca.repere.core

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BacMathTest {
    @Test fun absorptionAndEliminationStayNonnegative() {
        val start=OffsetDateTime.parse("2026-08-24T20:00:00-04:00")
        val drinks=listOf(BacDrink(start,30,13.45));val profile=BacProfile(75.0,.6,.015)
        val early=bacAt(drinks,profile,start.plusMinutes(15));val peak=peakBac(drinks,profile)!!
        val late=bacAt(drinks,profile,start.plusHours(24))
        assertTrue(peak>early);assertEquals(.299,peak*10,.01);assertEquals(0.0,late,0.000001)
    }

    @Test fun watsonRatioMatchesBounds() {
        assertTrue(distributionRatio("male",180.0,80.0) in .4..0.9)
        assertEquals(.55,distributionRatio("female",null,70.0),.0001)
    }

    @Test fun parsesServerLocalAndOfflineOffsetTimes() {
        assertEquals(13,parseDrinkTime("2026-08-27T13:43:00").hour)
        assertEquals(13,parseDrinkTime("2026-08-27T13:43:00-04:00").hour)
    }

    @Test fun trackedDayUsesConfiguredBoundary() {
        assertEquals(java.time.LocalDate.parse("2026-08-26"),trackedDay("2026-08-27T01:30:00-04:00",8))
        assertEquals(java.time.LocalDate.parse("2026-08-27"),trackedDay("2026-08-27T09:00:00-04:00",8))
        assertEquals(java.time.LocalDate.parse("2026-08-27"),trackedDay("2026-08-27T01:30:00-04:00",0))
    }

    @Test fun usualOnsetUsesTrackedDaysAcrossMidnight() {
        val times=listOf("2026-08-20T22:00:00-04:00","2026-08-21T01:00:00-04:00",
            "2026-08-21T23:00:00-04:00","2026-08-22T02:00:00-04:00","2026-08-22T22:30:00-04:00")
        assertEquals(22*60+30,usualOnsetMinutes(times,8))
    }
}
