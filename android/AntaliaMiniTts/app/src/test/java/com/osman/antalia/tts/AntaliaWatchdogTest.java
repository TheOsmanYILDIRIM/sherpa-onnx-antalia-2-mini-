package com.osman.antalia.tts;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AntaliaWatchdogTest {
    @Test public void timeoutRequiresBudgetAndNoProgress() {
        assertFalse(AntaliaCore.shouldTimeoutStage(1499, 1500, 7, 7, 1400, 1000));
        assertFalse(AntaliaCore.shouldTimeoutStage(1600, 1500, 8, 7, 1600, 1000));
        assertFalse(AntaliaCore.shouldTimeoutStage(1600, 1500, 7, 7, 999, 1000));
        assertTrue(AntaliaCore.shouldTimeoutStage(1601, 1500, 7, 7, 1001, 1000));
    }

    @Test public void progressedStageCannotBeKilledByOldHeartbeat() {
        assertFalse(AntaliaCore.shouldTimeoutStage(12000, 1500, 12, 11, 12000, 1000));
    }
}
