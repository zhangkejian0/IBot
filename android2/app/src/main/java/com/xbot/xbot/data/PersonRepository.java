package com.xbot.xbot.data;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local persistence for registered people (Room-backed). */
public class PersonRepository {
    private final PersonDao dao;
    private final Context appContext;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean loaded;
    private final List<PersonEntity> cache = new ArrayList<>();

    public PersonRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.dao = AppDatabase.getInstance(appContext).personDao();
    }

    public List<PersonEntity> getPeople() {
        return Collections.unmodifiableList(cache);
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void load(Runnable onComplete) {
        io.execute(() -> {
            cache.clear();
            cache.addAll(dao.getAll());
            loaded = true;
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    public void upsert(PersonEntity person, Runnable onComplete) {
        io.execute(() -> {
            dao.upsert(person);
            int idx = indexOf(person.id);
            if (idx >= 0) {
                cache.set(idx, person);
            } else {
                cache.add(person);
            }
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    public void delete(String id, Runnable onComplete) {
        io.execute(() -> {
            PersonEntity person = findInCache(id);
            dao.deleteById(id);
            cache.removeIf(p -> p.id.equals(id));
            String avatarPath = person != null ? person.avatarPath : null;
            if (avatarPath != null) {
                try {
                    File f = new File(avatarPath);
                    if (f.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                } catch (Exception ignored) {
                }
            }
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    public File avatarsDir() {
        File dir = new File(appContext.getFilesDir(), "avatars");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private int indexOf(String id) {
        for (int i = 0; i < cache.size(); i++) {
            if (cache.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private PersonEntity findInCache(String id) {
        for (PersonEntity p : cache) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }
}
