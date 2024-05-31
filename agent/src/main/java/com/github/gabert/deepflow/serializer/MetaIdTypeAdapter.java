package com.github.gabert.deepflow.serializer;

import com.google.gson.*;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

public class MetaIdTypeAdapter<T> extends TypeAdapter<T> {
    private final TypeAdapter<T> defaultAdapter;

    public MetaIdTypeAdapter(TypeAdapter<T> defaultAdapter) {
        this.defaultAdapter = defaultAdapter;
    }

    @Override
    public void write(JsonWriter out, T value) throws IOException {
        JsonElement jsonElement = defaultAdapter.toJsonTree(value);

        if (jsonElement.isJsonObject()) {
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            int systemId = System.identityHashCode(value);
            jsonObject.addProperty("__type__", value.getClass().getName());
            jsonObject.addProperty("__id__", systemId);
        } else if (jsonElement.isJsonArray()) {
            JsonObject jsonObject = new JsonObject();
            int systemId = System.identityHashCode(value);
            jsonObject.addProperty("__type__", value.getClass().getName());
            jsonObject.addProperty("__id__", systemId);
            jsonObject.add("__value__", jsonElement);
            jsonElement = jsonObject;
        } else if (jsonElement.isJsonPrimitive()) {
            JsonObject jsonObject = new JsonObject();
            int systemId = System.identityHashCode(value);
            jsonObject.addProperty("__type__", value.getClass().getName());
            jsonObject.addProperty("__id__", systemId);
            jsonObject.add("__value__", jsonElement);
            jsonElement = jsonObject;
        }

        Streams.write(jsonElement, out);
    }

    @Override
    public T read(JsonReader in) throws IOException {
        return defaultAdapter.read(in);
    }
}