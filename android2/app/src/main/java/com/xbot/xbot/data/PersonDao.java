package com.xbot.xbot.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface PersonDao {
    @Query("SELECT * FROM people ORDER BY createdAt ASC")
    List<PersonEntity> getAll();

    @Query("SELECT * FROM people WHERE id = :id LIMIT 1")
    PersonEntity findById(String id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(PersonEntity person);

    @Query("DELETE FROM people WHERE id = :id")
    void deleteById(String id);

    @Query("DELETE FROM people")
    void deleteAll();
}
