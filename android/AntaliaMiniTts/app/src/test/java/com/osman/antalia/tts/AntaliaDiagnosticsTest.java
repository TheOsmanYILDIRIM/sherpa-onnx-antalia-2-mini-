package com.osman.antalia.tts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class AntaliaDiagnosticsTest {
    @Test public void tailStartsAtWholeLineAndRespectsLimit() {
        byte[] source = "one\ntwo\nthree\nfour\n".getBytes(StandardCharsets.UTF_8);
        byte[] tail = AntaliaDiagnostics.tailAligned(source, 11);
        String text = new String(tail, StandardCharsets.UTF_8);
        assertEquals("three\nfour\n", text);
        assertTrue(tail.length <= 11);
    }

    @Test public void sanitizeDoesNotLeakMultilineStructure() {
        assertEquals("a b c", AntaliaDiagnostics.sanitize(" a\nb\tc "));
    }
}
