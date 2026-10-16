package nl.timonnl2.autocrafter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class WorkshopConfig {
    public record Point(int x, int y, int z) {
        public static Point of(BlockPos p) { return new Point(p.getX(), p.getY(), p.getZ()); }
        public BlockPos pos() { return new BlockPos(x, y, z); }
    }
    public record Region(String kind, Point first, Point second) {
        public BlockPos min() { return new BlockPos(Math.min(first.x, second.x), Math.min(first.y, second.y), Math.min(first.z, second.z)); }
        public BlockPos max() { return new BlockPos(Math.max(first.x, second.x), Math.max(first.y, second.y), Math.max(first.z, second.z)); }
        public long volume() { BlockPos a=min(), b=max(); return ((long)b.getX()-a.getX()+1)*((long)b.getY()-a.getY()+1)*((long)b.getZ()-a.getZ()+1); }
        public boolean contains(BlockPos p) { BlockPos a=min(), b=max(); return p.getX()>=a.getX() && p.getX()<=b.getX() && p.getY()>=a.getY() && p.getY()<=b.getY() && p.getZ()>=a.getZ() && p.getZ()<=b.getZ(); }
    }
    public List<Region> regions = new ArrayList<>();
    public Point staging;
    public String materials = "minecraft:repeater 64\nminecraft:comparator 32";
    public int delayTicks = 8;
    public boolean inspectShulkers = true;
    public boolean showRegions = true;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static String worldKey(Minecraft mc) {
        if (mc.level == null) return "offline";
        String world = mc.getCurrentServer() != null ? mc.getCurrentServer().ip
            : mc.getSingleplayerServer() != null ? mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().toString() : "local";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((world + "|" + mc.level.dimension().identifier()).getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static Path directory() { return FabricLoader.getInstance().getConfigDir().resolve("autocrafter-litematica"); }
    public static WorkshopConfig load(String key) throws java.io.IOException {
        Path path = directory().resolve(key + ".json");
        if (!Files.exists(path)) return new WorkshopConfig();
        WorkshopConfig config = GSON.fromJson(Files.readString(path), WorkshopConfig.class);
        if (config == null || config.regions == null || config.materials == null || config.regions.size() > 64) throw new java.io.IOException("Invalid workshop configuration");
        for (Region region : config.regions) if (region == null || region.first == null || region.second == null || region.volume() < 1 || region.volume() > 32768 || !Set.of("Input", "Output", "Work").contains(region.kind)) throw new java.io.IOException("Invalid stored region");
        config.delayTicks = Math.clamp(config.delayTicks, 4, 40);
        return config;
    }
    public void save(String key) throws java.io.IOException {
        Files.createDirectories(directory());
        Path path = directory().resolve(key + ".json"), temp = directory().resolve(key + ".tmp");
        Files.writeString(temp, GSON.toJson(this));
        try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
    }
}
