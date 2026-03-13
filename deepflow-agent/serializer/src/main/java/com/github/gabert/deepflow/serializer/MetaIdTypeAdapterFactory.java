package com.github.gabert.deepflow.serializer;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

public class MetaIdTypeAdapterFactory implements TypeAdapterFactory {
    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        TypeAdapter<T> defaultAdapter = gson.getDelegateAdapter(this, type);
        return new MetaIdTypeAdapter<>(defaultAdapter);
    }
}