package com.osman.antalia.tts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.speech.tts.TextToSpeech;
import org.junit.Test;

public final class AntaliaTtsStatusTest {
    @Test public void stoppedAndErrorRemainDistinctFrameworkStatuses() {
        assertNotEquals(TextToSpeech.SUCCESS, TextToSpeech.STOPPED);
        assertNotEquals(TextToSpeech.ERROR, TextToSpeech.STOPPED);
        assertNotEquals(TextToSpeech.SUCCESS, TextToSpeech.ERROR);
    }

    @Test public void knownFrameworkStatusValuesStayStableForBranching() {
        assertEquals(0, TextToSpeech.SUCCESS);
        assertEquals(-1, TextToSpeech.ERROR);
        assertEquals(-2, TextToSpeech.STOPPED);
    }
}
