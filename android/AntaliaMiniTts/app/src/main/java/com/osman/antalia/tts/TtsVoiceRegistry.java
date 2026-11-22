package com.osman.antalia.tts;

final class TtsVoiceRegistry {
    static final String PREF_DEFAULT_VOICE = "default_voice";
    static final String ANTALIA = "antalia-mini-tr-TR";
    static final String[] CLARVIA_IDS = {
        "male_1", "male_2", "female_1", "female_2", "female_3",
        "female_4", "female_5", "male_3", "male_4", "male_5"
    };

    private TtsVoiceRegistry() {}

    static String clarviaAndroidName(String id) {
        return "clarvia-" + id + "-tr-TR";
    }

    static String[] androidVoiceNames() {
        String[] result = new String[1 + CLARVIA_IDS.length];
        result[0] = ANTALIA;
        for (int i = 0; i < CLARVIA_IDS.length; i++) result[i + 1] = clarviaAndroidName(CLARVIA_IDS[i]);
        return result;
    }

    static String[] displayNames() {
        String[] result = new String[1 + CLARVIA_IDS.length];
        result[0] = "Antalia-2 Mini";
        for (int i = 0; i < CLARVIA_IDS.length; i++) result[i + 1] = "Clarvia Tiny · " + CLARVIA_IDS[i];
        return result;
    }

    static boolean isKnown(String name) {
        if (ANTALIA.equals(name)) return true;
        return clarviaId(name) != null;
    }

    static boolean isClarvia(String name) {
        return clarviaId(name) != null;
    }

    static String clarviaId(String androidName) {
        if (androidName == null) return null;
        for (String id : CLARVIA_IDS) if (clarviaAndroidName(id).equals(androidName)) return id;
        return null;
    }

    static int clarviaIndex(String androidName) {
        String id = clarviaId(androidName);
        if (id == null) throw new IllegalArgumentException("Not a Clarvia voice: " + androidName);
        for (int i = 0; i < CLARVIA_IDS.length; i++) if (CLARVIA_IDS[i].equals(id)) return i;
        throw new IllegalStateException("Clarvia voice index missing: " + id);
    }

    static String resolve(String requested, String configured) {
        if (isKnown(requested)) return requested;
        if (isKnown(configured)) return configured;
        return ANTALIA;
    }

    static int indexOf(String name) {
        String[] names = androidVoiceNames();
        for (int i = 0; i < names.length; i++) if (names[i].equals(name)) return i;
        return 0;
    }
}
