package com.github.gabert.deepflow.serializer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ReflectionAccessFilter;

public class GsonProvider {

    private static final Gson GSON = new GsonBuilder()
            .addReflectionAccessFilter(ReflectionAccessFilter.BLOCK_ALL_PLATFORM)
            .registerTypeAdapterFactory(new MetaIdTypeAdapterFactory())
            .create();

    public static Gson getGson() {
        return GSON;
    }
}