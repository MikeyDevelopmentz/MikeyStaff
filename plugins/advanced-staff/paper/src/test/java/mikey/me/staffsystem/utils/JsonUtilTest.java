package mikey.me.staffsystem.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonUtilTest {

    @Test
    void escapesAndReadsCompactJsonValues() {
        String json = "{\"text\":\"a\\\"b\\nc\",\"flag\":true,\"count\":-42}";

        assertEquals("a\\\"b\\nc", JsonUtil.escape("a\"b\nc"));
        assertEquals("a\"b\nc", JsonUtil.extractString(json, "text"));
        assertTrue(JsonUtil.extractBool(json, "flag", false));
        assertEquals(-42L, JsonUtil.extractLong(json, "count", 0L));
    }

    @Test
    void returnsDefaultsForMissingOrInvalidValues() {
        assertEquals("", JsonUtil.extractString("{}", "missing"));
        assertEquals(7L, JsonUtil.extractLong("{\"count\":\"bad\"}", "count", 7L));
        assertEquals(true, JsonUtil.extractBool("{}", "missing", true));
    }

    @Test
    void rejectsTrailingGarbageAndMalformedNumbers() {
        assertFalse(JsonUtil.extractBool("{\"flag\":truejunk}", "flag", false));
        assertFalse(JsonUtil.extractBool("{\"flag\":falsehood}", "flag", false));
        assertEquals(7L, JsonUtil.extractLong("{\"count\":42junk}", "count", 7L));
        assertEquals(7L, JsonUtil.extractLong("{\"count\":999999999999999999999999}", "count", 7L));
        assertEquals(7L, JsonUtil.extractLong("{\"count\":+42}", "count", 7L));
    }

    @Test
    void roundTripsControlCharactersAndEscapes() {
        String value = "slash\\quote\"line\n\r\t\b\f\u0000A";
        String json = "{\"value\":\"" + JsonUtil.escape(value) + "\"}";

        assertEquals(value, JsonUtil.extractString(json, "value"));
        assertEquals("", JsonUtil.extractString(null, "value"));
    }
}
