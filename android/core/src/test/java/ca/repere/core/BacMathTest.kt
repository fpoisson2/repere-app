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

    @Test fun oldFullyEliminatedDrinkMustNotCancelOutABrandNewOne() {
        // Day starts at 8h; a drink from last night is long metabolized by the time a fresh one
        // is logged at 7h and checked 15 minutes later, at 7h15 — still before the day boundary.
        val lastNight=BacDrink(OffsetDateTime.parse("2026-08-28T21:00:00-04:00"),30,13.45)
        val fresh=BacDrink(OffsetDateTime.parse("2026-08-29T07:00:00-04:00"),30,13.45)
        val now=OffsetDateTime.parse("2026-08-29T07:15:00-04:00")
        val profile=BacProfile(75.0,.6,.015)
        assertEquals(0.0,bacAt(listOf(lastNight),profile,now),0.000001)
        assertTrue(bacAt(listOf(fresh),profile,now)>0.0)
        assertEquals(bacAt(listOf(fresh),profile,now),bacAt(listOf(lastNight,fresh),profile,now),0.000001)
    }

    @Test fun stillActiveDrinkKeepsAbsorbingInsteadOfUsingItsStaleZeroDuration() {
        // A drink started from Wear OS is stored with duration_minutes=0 until it's finished, so
        // while it's still marked active the absorption window must track real elapsed time
        // instead of assuming it was downed in the default 30-minute window.
        val start=OffsetDateTime.parse("2026-08-29T07:00:00-04:00")
        val moment=start.plusMinutes(45)
        val profile=BacProfile(75.0,.6,.015)
        val active=bacAt(listOf(BacDrink(start,0,13.45,active=true)),profile,moment)
        val finished=bacAt(listOf(BacDrink(start,0,13.45,active=false)),profile,moment)
        assertTrue(active<finished)
    }

    @Test fun recentForBacDropsDrinksOutsideTheWindow() {
        val now=OffsetDateTime.parse("2026-08-29T18:00:00-04:00")
        val monthOldDrink=BacDrink(now.minusDays(30),30,13.45)
        val freshDrink=BacDrink(now.minusMinutes(20),30,13.45)
        assertEquals(listOf(freshDrink),recentForBac(listOf(monthOldDrink,freshDrink),now))
    }

    @Test fun bacUsesCanonicalLocalWallTimeAcrossLegacyOffsets() {
        val drink=OffsetDateTime.parse("2026-08-28T22:00:00Z")
        val localNow=OffsetDateTime.parse("2026-08-28T22:30:00-04:00")
        val value=bacAt(listOf(BacDrink(drink,30,13.45)),BacProfile(75.0,.6,.015),localNow)
        assertTrue(value>0.0)
    }

    @Test fun usualOnsetUsesTrackedDaysAcrossMidnight() {
        val times=listOf("2026-08-20T22:00:00-04:00","2026-08-21T01:00:00-04:00",
            "2026-08-21T23:00:00-04:00","2026-08-22T02:00:00-04:00","2026-08-22T22:30:00-04:00")
        assertEquals(22*60+30,usualOnsetMinutes(times,8))
    }
}
