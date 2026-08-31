package android.os;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class Bundle implements Serializable, Cloneable {
    public static final Bundle EMPTY = new Bundle();

    private final Map<String, Object> map;

    public Bundle() {
        this.map = new HashMap<>();
    }

    public Bundle(Bundle other) {
        this.map = other != null ? new HashMap<>(other.map) : new HashMap<>();
    }

    public void putBoolean(String key, boolean value) {
        map.put(key, value);
    }

    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object val = map.get(key);
        if (val instanceof Boolean) {
            return (Boolean) val;
        }
        return defaultValue;
    }

    public void putInt(String key, int value) {
        map.put(key, value);
    }

    public int getInt(String key) {
        return getInt(key, 0);
    }

    public int getInt(String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Integer) {
            return (Integer) val;
        }
        return defaultValue;
    }

    public void putLong(String key, long value) {
        map.put(key, value);
    }

    public long getLong(String key) {
        return getLong(key, 0L);
    }

    public long getLong(String key, long defaultValue) {
        Object val = map.get(key);
        if (val instanceof Long) {
            return (Long) val;
        }
        return defaultValue;
    }

    public void putFloat(String key, float value) {
        map.put(key, value);
    }

    public float getFloat(String key) {
        return getFloat(key, 0.0f);
    }

    public float getFloat(String key, float defaultValue) {
        Object val = map.get(key);
        if (val instanceof Float) {
            return (Float) val;
        }
        return defaultValue;
    }

    public void putString(String key, String value) {
        map.put(key, value);
    }

    public String getString(String key) {
        Object val = map.get(key);
        return val instanceof String ? (String) val : null;
    }

    public String getString(String key, String defaultValue) {
        Object val = map.get(key);
        return val instanceof String ? (String) val : defaultValue;
    }

    public boolean containsKey(String key) {
        return map.containsKey(key);
    }

    public Object get(String key) {
        return map.get(key);
    }

    public Set<String> keySet() {
        return map.keySet();
    }

    public void clear() {
        map.clear();
    }

    public int size() {
        return map.size();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }
}
