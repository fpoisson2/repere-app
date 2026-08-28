package ca.repere.data

import org.junit.Assert.*
import org.junit.Test

class OfflineModelsTest {
    @Test fun freshInstallDefaultsNeedNoServer() {
        val settings=LocalSettings()
        assertEquals(8,settings.dayStartHour)
        assertEquals(8.0,settings.sessionGapHours,0.0)
        assertFalse(settings.dirty)
    }

    @Test fun queuedOperationsKeepOrderingAndErrors() {
        val create=PendingApiOperation("1","/api/goals","POST","{}",createdAt=10)
        val delete=PendingApiOperation("2","/api/goals/1","DELETE","{}",createdAt=20)
        assertTrue(create.createdAt<delete.createdAt)
        val failed=create.copy(attempts=create.attempts+1,lastError="Hors ligne")
        assertEquals(1,failed.attempts)
        assertEquals("Hors ligne",failed.lastError)
    }

    @Test fun localEntitiesAreDirtyUntilSynchronization() {
        val goal=GoalEntity("local",null,"max_grams_week",100.0,startedOn="2026-08-27")
        val checkIn=CheckInEntity("id","2026-08-27","{}")
        assertTrue(goal.dirty)
        assertTrue(checkIn.dirty)
        assertNull(goal.serverId)
    }
}
