package ca.repere.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AlcoholTest {
    @Test fun canadianExamplesMatchServerRules() {
        assertEquals(18.65985,alcoholGrams(473.0,5.0),0.00001)
        assertEquals(1.387,canadianStandards(473.0,5.0),0.001)
        assertEquals(5.72,canadianStandards(750.0,13.0),0.01)
    }
}
