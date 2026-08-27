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
        assertTrue(peak>early);assertEquals(0.0,late,0.000001)
    }

    @Test fun watsonRatioMatchesBounds() {
        assertTrue(distributionRatio("male",180.0,80.0) in .4..0.9)
        assertEquals(.55,distributionRatio("female",null,70.0),.0001)
    }
}
