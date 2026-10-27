package in.smartvrms.desktop.integration;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.HashSet;

/** Gson handles JSON syntax. The preliminary walk rejects duplicate keys before tree construction. */
final class DesktopJson {
    private DesktopJson() { }
    static JsonObject object(byte[] bytes) {
        try {
            if(bytes.length>65536) throw new IOException();
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try(JsonReader reader=reader(text)) {
                walk(reader); if(reader.peek()!=JsonToken.END_DOCUMENT) throw new IOException();
            }
            try(JsonReader reader=reader(text)) {
                JsonElement result=JsonParser.parseReader(reader);
                if(!result.isJsonObject()) throw new IOException(); return result.getAsJsonObject();
            }
        } catch(IOException|IllegalStateException|JsonParseException ex) { throw new IllegalStateException("Invalid authentication response."); }
    }
    private static JsonReader reader(String text) { JsonReader r=new JsonReader(new StringReader(text));r.setStrictness(Strictness.STRICT);r.setNestingLimit(32);return r; }
    private static void walk(JsonReader r) throws IOException {
        switch(r.peek()) {
            case BEGIN_OBJECT -> {r.beginObject();var keys=new HashSet<String>();while(r.hasNext()){if(!keys.add(r.nextName()))throw new IOException();walk(r);}r.endObject();}
            case BEGIN_ARRAY -> {r.beginArray();while(r.hasNext())walk(r);r.endArray();}
            case STRING,NUMBER -> r.nextString();
            case BOOLEAN -> r.nextBoolean();
            case NULL -> r.nextNull();
            default -> throw new IOException();
        }
    }
    static String string(JsonObject o,String name) {
        JsonElement v=o.get(name);
        if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString()) throw new IllegalStateException("Invalid authentication response.");
        return v.getAsString();
    }
    static boolean bool(JsonObject o,String name) {
        JsonElement v=o.get(name);
        if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isBoolean())throw new IllegalStateException("Invalid authentication response.");
        return v.getAsBoolean();
    }
    static long longVal(JsonObject o,String name) {
        JsonElement v=o.get(name);
        if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber()) throw new IllegalStateException("Invalid authentication response.");
        return v.getAsLong();
    }
    static JsonObject object(JsonObject o,String name) {
        JsonElement v=o.get(name);
        if(v==null||!v.isJsonObject()) throw new IllegalStateException("Invalid authentication response.");
        return v.getAsJsonObject();
    }
}
