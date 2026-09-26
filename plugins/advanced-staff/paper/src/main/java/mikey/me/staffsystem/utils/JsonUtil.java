package mikey.me.staffsystem.utils;

public final class JsonUtil {

    private JsonUtil() {}

    public static String escape(String value) {
        return mikey.me.core.json.JsonUtil.escape(value);
    }

    public static String extractString(String json, String key) {
        return mikey.me.core.json.JsonUtil.extractString(json, key);
    }

    public static boolean extractBool(String json, String key, boolean def) {
        return mikey.me.core.json.JsonUtil.extractBool(json, key, def);
    }

    public static boolean hasKey(String json, String key) {
        return mikey.me.core.json.JsonUtil.hasKey(json, key);
    }

    public static long extractLong(String json, String key, long def) {
        return mikey.me.core.json.JsonUtil.extractLong(json, key, def);
    }

    public static int extractInt(String json, String key, int def) {
        return mikey.me.core.json.JsonUtil.extractInt(json, key, def);
    }
}
