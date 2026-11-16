package com.osman.antalia.tts;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.junit.Test;

public final class AntaliaCoreSeamTest {
    @Test public void constantSignalDoesNotDipAtInternalSeam() {
        float[] left = new float[400];
        float[] right = new float[400];
        Arrays.fill(left, 0.75f);
        Arrays.fill(right, 0.75f);
        int overlap = AntaliaCore.crossfadeTail(left, right);
        assertEquals(240, overlap);
        for (int i = left.length - overlap; i < left.length; i++) {
            assertEquals(0.75f, left[i], 1e-6f);
        }
    }

    @Test public void stageBudgetTracksHealthyDeviceLatency() {
        assertEquals(6000L, AntaliaCore.stageBudgetMs("text", -1L));
        assertEquals(8000L, AntaliaCore.stageBudgetMs("flow", -1L));
        assertEquals(10000L, AntaliaCore.stageBudgetMs("vocoder", -1L));
        assertEquals(1500L, AntaliaCore.stageBudgetMs("flow", 200L));
        assertEquals(3750L, AntaliaCore.stageBudgetMs("flow", 1000L));
        assertEquals(12000L, AntaliaCore.stageBudgetMs("vocoder", 6000L));
    }

    @Test public void seamTransitionsInsteadOfDroppingToZero() {
        float[] left = new float[300];
        float[] right = new float[300];
        Arrays.fill(left, 1.0f);
        Arrays.fill(right, 0.5f);
        int overlap = AntaliaCore.crossfadeTail(left, right);
        int base = left.length - overlap;
        for (int i = base; i < left.length; i++) {
            if (i > base) {
                // A smooth monotonic blend replaces the old forced zero at the seam.
                org.junit.Assert.assertTrue(left[i] <= left[i - 1] + 1e-6f);
            }
            org.junit.Assert.assertTrue(left[i] > 0.5f);
        }
    }
}
