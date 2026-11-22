package com.osman.antalia.tts;

import static org.junit.Assert.*;
import org.junit.Test;

public class TtsVoiceRegistryTest {
    @Test public void exposesAntaliaAndTenClarviaVoices() {
        String[] names = TtsVoiceRegistry.androidVoiceNames();
        assertEquals(11, names.length);
        assertEquals("antalia-mini-tr-TR", names[0]);
        assertEquals("clarvia-male_1-tr-TR", names[1]);
        assertEquals("clarvia-male_5-tr-TR", names[10]);
    }

    @Test public void mapsClarviaVoiceToModelIndex() {
        assertEquals(0, TtsVoiceRegistry.clarviaIndex("clarvia-male_1-tr-TR"));
        assertEquals(2, TtsVoiceRegistry.clarviaIndex("clarvia-female_1-tr-TR"));
        assertEquals(9, TtsVoiceRegistry.clarviaIndex("clarvia-male_5-tr-TR"));
    }

    @Test public void configuredVoiceIsFallbackForClientsWithoutVoiceLists() {
        assertEquals("clarvia-female_4-tr-TR",
            TtsVoiceRegistry.resolve(null, "clarvia-female_4-tr-TR"));
        assertEquals(TtsVoiceRegistry.ANTALIA,
            TtsVoiceRegistry.resolve("unknown", "unknown"));
    }
}
