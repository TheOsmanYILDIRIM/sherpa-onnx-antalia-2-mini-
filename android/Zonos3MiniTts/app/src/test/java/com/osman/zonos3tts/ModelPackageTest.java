package com.osman.zonos3tts;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModelPackageTest {
    @Test public void acceptsOnlyFlatExpectedNames(){
        assertTrue(ModelPackage.isAllowedEntryName("zonos3-package.json"));
        assertTrue(ModelPackage.isAllowedEntryName("backbone-q2_k.gguf"));
        assertTrue(ModelPackage.isAllowedEntryName("mmproj-q4_k_m.gguf"));
        assertFalse(ModelPackage.isAllowedEntryName("../evil.gguf"));
        assertFalse(ModelPackage.isAllowedEntryName("dir/model.gguf"));
        assertFalse(ModelPackage.isAllowedEntryName("model.bin"));
    }
}
