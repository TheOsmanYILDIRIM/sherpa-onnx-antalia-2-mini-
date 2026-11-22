package com.osman.antalia.tts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ClarviaWatchdogTest {
    @Test public void firstAttemptAndRetryUseBoundedBudgets() {
        assertEquals(4000L, ClarviaCore.onnxBudgetMs(0));
        assertEquals(5000L, ClarviaCore.onnxBudgetMs(1));
        assertEquals(5000L, ClarviaCore.onnxBudgetMs(5));
    }

    @Test public void retryOccursOnlyOnceAndNeverAfterStop() {
        assertTrue(ClarviaCore.shouldRetryInference(false, 0));
        assertFalse(ClarviaCore.shouldRetryInference(false, 1));
        assertFalse(ClarviaCore.shouldRetryInference(true, 0));
    }
}
