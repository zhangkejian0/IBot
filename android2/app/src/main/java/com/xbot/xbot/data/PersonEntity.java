package com.xbot.xbot.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import java.util.ArrayList;
import java.util.List;

/** Room entity for a registered person identity. */
@Entity(tableName = "people")
public class PersonEntity {
    @PrimaryKey
    @NonNull
    public String id;

    public String name;
    public String relation;
    public String embeddingsJson;
    public String avatarPath;
    public long createdAt;

    public PersonEntity() {
        this.id = "";
        this.embeddingsJson = "[]";
        this.createdAt = System.currentTimeMillis();
    }

    @Ignore
    public PersonEntity(
            String id,
            String name,
            String relation,
            List<List<Double>> embeddings,
            String avatarPath,
            long createdAt) {
        this.id = id;
        this.name = name;
        this.relation = relation;
        this.embeddingsJson = RoomConverters.embeddingsToJson(
                embeddings != null ? embeddings : new ArrayList<>());
        this.avatarPath = avatarPath;
        this.createdAt = createdAt;
    }

    public int getSampleCount() {
        return RoomConverters.embeddingsFromJson(embeddingsJson).size();
    }
}
