package com.xbot.xbot.data;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import androidx.room.TypeConverter;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/** Room type converters for complex fields. */
public final class RoomConverters {
    private static final Gson GSON = new Gson();
    private static final Type EMBEDDINGS_TYPE =
            new TypeToken<List<List<Double>>>() {}.getType();

    private RoomConverters() {}

    @TypeConverter
    public static String embeddingsToJson(List<List<Double>> embeddings) {
        return GSON.toJson(embeddings != null ? embeddings : new ArrayList<>());
    }

    @TypeConverter
    public static List<List<Double>> embeddingsFromJson(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        List<List<Double>> parsed = GSON.fromJson(json, EMBEDDINGS_TYPE);
        return parsed != null ? parsed : new ArrayList<>();
    }
}
