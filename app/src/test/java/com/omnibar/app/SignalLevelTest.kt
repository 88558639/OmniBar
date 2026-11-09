package com.omnibar.app

import com.omnibar.app.hook.SignalWatch
import com.omnibar.app.hook.SystemStateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signal-bar hysteresis. `SignalStrength.level` is an instantaneous quantisation of
 * RSRP and flips between adjacent bars while the phone is stationary, so candidates
 * must survive a settle window before they reach the indicator.
 */
class SignalLevelTest {

    @Test fun aReadingEqualToTheCommittedLevelCancelsThePendingChange() {
        assertNull(SystemStateSource.signalPending(SignalWatch(1, 1000L, 1000L), 3, 3, 2000L))
        assertNull(SystemStateSource.signalPending(null, 3, 3, 2000L))
    }

    @Test fun aNewWindowStartsWithItsOwnVoteAndTimestamp() {
        val watch = SystemStateSource.signalPending(null, 4, 3, 5000L)!!
        assertEquals(4, watch.level)
        assertEquals(5000L, watch.since)
        assertEquals(5000L, watch.windowStart)
        assertEquals(1, watch.votes[4])
    }

    @Test fun aDifferentCandidateResetsItsStabilityClockButNotTheWindow() {
        val previous = SignalWatch(4, 1000L, 1000L)
        val watch = SystemStateSource.signalPending(previous, 1, 3, 2000L)!!
        assertEquals(1, watch.level)
        assertEquals(2000L, watch.since)
        assertEquals(1000L, watch.windowStart)
        assertEquals(1, watch.votes[4])
        assertEquals(1, watch.votes[1])
    }

    @Test fun aStableCandidateKeepsItsOriginalTimestamp() {
        val first = SignalWatch(1, 1000L, 1000L)
        val watch = SystemStateSource.signalPending(first, 1, 3, 4000L)!!
        assertEquals(1000L, watch.since)
        assertEquals(2, watch.votes[1])
        assertEquals(first, watch)
    }

    @Test fun risingIsFasterThanDropping() {
        assertFalse(SystemStateSource.signalLevelSettled(4, 3, 2400L))
        assertTrue(SystemStateSource.signalLevelSettled(4, 3, 2600L))
        assertFalse(SystemStateSource.signalLevelSettled(1, 3, 4900L))
        assertTrue(SystemStateSource.signalLevelSettled(1, 3, 5100L))
        assertFalse(SystemStateSource.signalLevelSettled(3, 3, 99999L))
    }

    @Test fun anOscillatingWindowConvergesOnTheDominantLevel() {
        val votes = IntArray(5)
        votes[4] = 9
        votes[2] = 3
        assertEquals(4, SystemStateSource.signalDominantLevel(votes, 3))
    }

    @Test fun tiesGoToTheLevelClosestToTheCommittedOneThenToTheHigher() {
        val votes = IntArray(5)
        votes[4] = 5
        votes[2] = 5
        assertEquals(4, SystemStateSource.signalDominantLevel(votes, 3))
        votes[4] = 0
        assertEquals(2, SystemStateSource.signalDominantLevel(votes, 3))
    }

    @Test fun anEmptyVoteTallyKeepsTheCommittedLevel() {
        assertEquals(3, SystemStateSource.signalDominantLevel(IntArray(5), 3))
    }
}
