package android.util;
import java.io.*;
import java.util.*;
import org.json.*;
/** JVM adapter for exercising the store schema; Android supplies the streaming parser. */
public final class JsonReader implements Closeable {
    private final Reader input;
    private final List<Object[]> events = new ArrayList<>();
    private int position;
    public JsonReader(Reader input) throws IOException {
        this.input = input;
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[8192];
        int read;
        while ((read = input.read(buffer)) != -1) text.append(buffer, 0, read);
        try {
            JSONTokener tokener = new JSONTokener(text.toString());
            add(tokener.nextValue());
            if (tokener.nextClean() != 0) throw new IOException("trailing data");
            events.add(new Object[] { JsonToken.END_DOCUMENT, null });
        } catch (JSONException bad) { throw new IOException(bad); }
    }
    private void event(JsonToken token, Object value) { events.add(new Object[] {token, value}); }
    private void add(Object value) {
        if (value instanceof JSONObject) {
            event(JsonToken.BEGIN_OBJECT, null);
            JSONObject object = (JSONObject) value;
            for (String key : object.keySet()) { event(JsonToken.NAME, key); add(object.get(key)); }
            event(JsonToken.END_OBJECT, null);
        } else if (value instanceof JSONArray) {
            event(JsonToken.BEGIN_ARRAY, null);
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) add(array.get(i));
            event(JsonToken.END_ARRAY, null);
        } else if (value == JSONObject.NULL) event(JsonToken.NULL, null);
        else if (value instanceof Number) event(JsonToken.NUMBER, value.toString());
        else if (value instanceof Boolean) event(JsonToken.BOOLEAN, value.toString());
        else event(JsonToken.STRING, value);
    }
    public void setLenient(boolean lenient) { }
    public JsonToken peek() { return (JsonToken) events.get(position)[0]; }
    private void expect(JsonToken token) throws IOException {
        if (peek() != token) throw new IOException("Expected " + token + " got " + peek());
        position++;
    }
    public void beginObject() throws IOException { expect(JsonToken.BEGIN_OBJECT); }
    public void endObject() throws IOException { expect(JsonToken.END_OBJECT); }
    public void beginArray() throws IOException { expect(JsonToken.BEGIN_ARRAY); }
    public void endArray() throws IOException { expect(JsonToken.END_ARRAY); }
    public boolean hasNext() { return peek() != JsonToken.END_OBJECT && peek() != JsonToken.END_ARRAY; }
    public String nextName() throws IOException {
        String result = (String) events.get(position)[1]; expect(JsonToken.NAME); return result;
    }
    public String nextString() throws IOException {
        if (peek() != JsonToken.STRING && peek() != JsonToken.NUMBER) throw new IOException("Not string");
        return (String) events.get(position++)[1];
    }
    public void close() throws IOException { input.close(); }
}
