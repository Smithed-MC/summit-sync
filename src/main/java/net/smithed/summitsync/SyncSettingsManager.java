package net.smithed.summitsync;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.PackType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import java.io.Reader;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SyncSettingsManager {
    private static final Gson GSON = new com.google.gson.GsonBuilder()
        .registerTypeAdapter(Identifier.class, new com.google.gson.TypeAdapter<Identifier>() {
            @Override
            public void write(com.google.gson.stream.JsonWriter out, Identifier value) throws java.io.IOException {
                if (value == null) {
                    out.nullValue();
                } else {
                    out.value(value.toString());
                }
            }

            @Override
            public Identifier read(com.google.gson.stream.JsonReader in) throws java.io.IOException {
                if (in.peek() == com.google.gson.stream.JsonToken.NULL) {
                    in.nextNull();
                    return null;
                }
                return Identifier.parse(in.nextString());
            }
        })
        .create();

    private static final Set<String> SCORES_TO_SYNC = new HashSet<>();
    private static final List<DatabaseSettings> DATABASES_TO_SYNC = new java.util.ArrayList<>();

    public static void register() {
        ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(SummitSync.id("sync-settings"), (ResourceManagerReloadListener) manager -> {
            synchronized (SCORES_TO_SYNC) {
                SCORES_TO_SYNC.clear();
                synchronized (DATABASES_TO_SYNC) {
                    DATABASES_TO_SYNC.clear();
                }
                Map<Identifier, Resource> resources = manager.listResources("sync_settings", path -> path.getPath().endsWith(".json"));
                for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
                    Identifier id = entry.getKey();
                    Resource resource = entry.getValue();
                    try (Reader reader = resource.openAsReader()) {
                        SyncSettingsData data = GSON.fromJson(reader, SyncSettingsData.class);
                        if (data != null) {
                            if (data.scores != null) {
                                SCORES_TO_SYNC.addAll(data.scores);
                                SummitSync.LOGGER.info("Loaded {} scoreboard objectives to sync from {}", data.scores.size(), id);
                            }
                            if (data.databases != null) {
                                synchronized (DATABASES_TO_SYNC) {
                                    DATABASES_TO_SYNC.addAll(data.databases);
                                }
                                SummitSync.LOGGER.info("Loaded {} command databases to sync from {}", data.databases.size(), id);
                            }
                        }
                    } catch (Exception e) {
                        SummitSync.LOGGER.error("Failed to parse sync settings from " + id, e);
                    }
                }
                SummitSync.LOGGER.info("Total scoreboard objectives registered for sync: {}", SCORES_TO_SYNC.size());
                SummitSync.LOGGER.info("Total command databases registered for sync: {}", DATABASES_TO_SYNC.size());
            }
        });
    }

    public static Set<String> getScoresToSync() {
        synchronized (SCORES_TO_SYNC) {
            return new HashSet<>(SCORES_TO_SYNC);
        }
    }

    public static List<DatabaseSettings> getDatabasesToSync() {
        synchronized (DATABASES_TO_SYNC) {
            return new java.util.ArrayList<>(DATABASES_TO_SYNC);
        }
    }

    public static class DatabaseSettings {
        @SerializedName("key")
        public Identifier key;
        @SerializedName("on_sync")
        public Identifier onSync;
    }

    private static class SyncSettingsData {
        @SerializedName("scores")
        public List<String> scores;
        @SerializedName("databases")
        public List<DatabaseSettings> databases;
    }
}
