package android.util;
import java.io.*;
import java.util.*;
import org.json.JSONObject;
/** Streaming JSON writer shim; deliberately never builds a full object graph. */
public final class JsonWriter implements Closeable {
    private final Writer output;
    private final Deque<int[]> levels = new ArrayDeque<>();
    private boolean valueAfterName;
    public JsonWriter(Writer output) { this.output = output; }
    private void beforeValue() throws IOException {
        if (valueAfterName) { valueAfterName = false; return; }
        if (!levels.isEmpty() && levels.peek()[0]++ > 0) output.write(',');
    }
    public JsonWriter beginObject() throws IOException { beforeValue(); output.write('{'); levels.push(new int[1]); return this; }
    public JsonWriter endObject() throws IOException { output.write('}'); levels.pop(); return this; }
    public JsonWriter beginArray() throws IOException { beforeValue(); output.write('['); levels.push(new int[1]); return this; }
    public JsonWriter endArray() throws IOException { output.write(']'); levels.pop(); return this; }
    public JsonWriter name(String name) throws IOException {
        if (levels.peek()[0]++ > 0) output.write(',');
        output.write(JSONObject.quote(name)); output.write(':'); valueAfterName = true; return this;
    }
    public JsonWriter value(String value) throws IOException { beforeValue(); output.write(JSONObject.quote(value)); return this; }
    public JsonWriter value(long value) throws IOException { beforeValue(); output.write(Long.toString(value)); return this; }
    public void close() throws IOException { output.close(); }
}
