package com.mrpotato.oldf3debugscreen.client.debug;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mojang.blaze3d.platform.GLX;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.datafixers.DataFixUtils;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.ClientBrandRetriever;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Environment(EnvType.CLIENT)
public final class OldDebugHudProvider {

    private static final Map<Heightmap.Types, String> HEIGHTMAP_NAMES = Maps.newEnumMap(Map.of(
            Heightmap.Types.WORLD_SURFACE_WG,          "SW",
            Heightmap.Types.WORLD_SURFACE,             "S",
            Heightmap.Types.OCEAN_FLOOR_WG,            "OW",
            Heightmap.Types.OCEAN_FLOOR,               "O",
            Heightmap.Types.MOTION_BLOCKING,           "M",
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, "ML"
    ));

    private OldDebugHudProvider() {}

    // Left panel
    public static List<String> getLeftText(DebugHudContext ctx) {
        Minecraft client = ctx.client();
        IntegratedServer integratedServer = client.getSingleplayerServer();
        ClientPacketListener networkHandler = client.getConnection();

        float packetsSent     = 0f;
        float packetsReceived = 0f;
        if (networkHandler != null) {
            Connection connection = networkHandler.getConnection();
            packetsSent     = getAveragePacketsSent(connection);
            packetsReceived = getAveragePacketsReceived(connection);
        }

        TickRateManager tickManager = getWorld(client).tickRateManager();
        String tickState;
        if (tickManager.isSteppingForward()) {
            tickState = " (frozen - stepping)";
        } else if (tickManager.isFrozen()) {
            tickState = " (frozen)";
        } else {
            tickState = "";
        }

        String serverLine;
        if (integratedServer != null) {
            ServerTickRateManager stm = integratedServer.tickRateManager();
            boolean sprinting = stm.isSprinting();
            if (sprinting) tickState = " (sprinting)";
            String mspt = sprinting ? "-" : String.format(Locale.ROOT, "%.1f", getMillisPerTick(tickManager));
            serverLine = String.format(Locale.ROOT,
                    "Integrated server @ %.1f/%s ms%s, %.0f tx, %.0f rx",
                    getServerAvgTickTime(integratedServer), mspt, tickState, packetsSent, packetsReceived);
        } else {
            String brand = networkHandler != null ? getNetworkBrand(networkHandler) : "N/A";
            serverLine = String.format(Locale.ROOT,
                    "\"%s\" server%s, %.0f tx, %.0f rx",
                    brand, tickState, packetsSent, packetsReceived);
        }

        BlockPos blockPos = client.getCameraEntity().blockPosition();

        // Reduced debug info
        if (client.showOnlyReducedInfo()) {
            return Lists.newArrayList(
                    "Minecraft " + SharedConstants.getCurrentVersion().name()
                            + " (" + client.getLaunchedVersion() + "/" + ClientBrandRetriever.getClientModName() + ")",
                    buildFpsString(client),
                    serverLine,
                    getChunksDebugString(client),
                    getEntitiesDebugString(client),
                    "P: " + getParticleDebugString(client) + ". T: " + client.level.getEntityCount(),
                    client.level.toString(),
                    "",
                    String.format(Locale.ROOT, "Chunk-relative: %d %d %d",
                            blockPos.getX() & 15, blockPos.getY() & 15, blockPos.getZ() & 15)
            );
        }

        // Full debug info
        Entity entity = client.getCameraEntity();
        Direction direction = entity.getDirection();
        String facingDesc = switch (direction) {
            case NORTH -> "Towards negative Z";
            case SOUTH -> "Towards positive Z";
            case WEST  -> "Towards negative X";
            case EAST  -> "Towards positive X";
            default    -> "Invalid";
        };

        ChunkPos chunkPos = ctx.chunkPos();
        Level world = getWorld(client);
        LongSet forcedChunks = world instanceof ServerLevel sw ? sw.getForceLoadedChunks() : LongSets.EMPTY_SET;

        List<String> list = Lists.newArrayList(
                "Minecraft " + SharedConstants.getCurrentVersion().name()
                        + " (" + client.getLaunchedVersion() + "/" + ClientBrandRetriever.getClientModName()
                        + ("release".equalsIgnoreCase(client.getVersionType()) ? "" : "/" + client.getVersionType()) + ")",
                buildFpsString(client),
                serverLine,
                getChunksDebugString(client),
                getEntitiesDebugString(client),
                "P: " + getParticleDebugString(client) + ". T: " + client.level.getEntityCount(),
                client.level.toString()
        );

        String serverWorldStr = getServerWorldDebugString(client);
        if (serverWorldStr != null) list.add(serverWorldStr);

        list.add(client.level.dimension().identifier() + " FC: " + forcedChunks.size());
        List<String> feedbackLines = DebugFeedbackOverlay.getActiveMessages();
        if (!feedbackLines.isEmpty()) {
            list.add("");
            list.addAll(feedbackLines);
        }
        list.add("");
        list.add(String.format(Locale.ROOT, "XYZ: %.3f / %.5f / %.3f",
                entity.getX(), entity.getY(), entity.getZ()));
        list.add(String.format(Locale.ROOT, "Block: %d %d %d [%d %d %d]",
                blockPos.getX(), blockPos.getY(), blockPos.getZ(),
                blockPos.getX() & 15, blockPos.getY() & 15, blockPos.getZ() & 15));
        list.add(String.format(Locale.ROOT, "Chunk: %d %d %d [%d %d in r.%d.%d.mca]",
                chunkPos.x(), SectionPos.blockToSectionCoord(blockPos.getY()), chunkPos.z(),
                chunkPos.getRegionLocalX(), chunkPos.getRegionLocalZ(),
                chunkPos.getRegionX(), chunkPos.getRegionZ()));
        list.add(String.format(Locale.ROOT, "Facing: %s (%s) (%.1f / %.1f)",
                direction, facingDesc,
                Mth.wrapDegrees(entity.getYRot()), Mth.wrapDegrees(entity.getXRot())));

        LevelChunk clientChunk = ctx.clientChunk();
        if (clientChunk == null || clientChunk.isEmpty()) {
            list.add("Waiting for chunk...");
        } else {
            int combinedLight = client.level.getLightEngine().getRawBrightness(blockPos, 0);
            int skyLight      = client.level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(blockPos);
            int blockLight    = client.level.getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(blockPos);
            list.add("Client Light: " + combinedLight + " (" + skyLight + " sky, " + blockLight + " block)");

            LevelChunk serverChunk = ctx.serverChunk();

            StringBuilder sb = new StringBuilder("CH");
            for (Heightmap.Types t : Heightmap.Types.values()) {
                if (t.sendToClient()) {
                    sb.append(" ").append(HEIGHTMAP_NAMES.get(t))
                      .append(": ").append(clientChunk.getHeight(t, blockPos.getX(), blockPos.getZ()));
                }
            }
            list.add(sb.toString());

            sb.setLength(0);
            sb.append("SH");
            for (Heightmap.Types t : Heightmap.Types.values()) {
                if (t.keepAfterWorldgen()) {
                    sb.append(" ").append(HEIGHTMAP_NAMES.get(t)).append(": ");
                    if (serverChunk != null) {
                        sb.append(serverChunk.getHeight(t, blockPos.getX(), blockPos.getZ()));
                    } else {
                        sb.append("??");
                    }
                }
            }
            list.add(sb.toString());

            if (!client.level.isOutsideBuildHeight(blockPos.getY())) {
                Holder<Biome> biome = client.level.getBiome(blockPos);
                list.add("Biome: " + getBiomeString(biome));
                if (serverChunk != null) {
                    float moonSize = world instanceof ServerLevel sw2 ? sw2.getMoonBrightness(blockPos) : 0.0f;
                    long inhabitedTime = serverChunk.getInhabitedTime();
                    DifficultyInstance ld = new DifficultyInstance(
                            world.getDifficulty(), world.getOverworldClockTime(), inhabitedTime, moonSize);
                    list.add(String.format(Locale.ROOT, "Local Difficulty: %.2f // %.2f (Day %d)",
                            ld.getEffectiveDifficulty(), ld.getSpecialMultiplier(),
                            client.level.getOverworldClockTime() / 24000L));
                } else {
                    list.add("Local Difficulty: ??");
                }
            }

            if (serverChunk != null && serverChunk.isOldNoiseGeneration()) {
                list.add("Blending: Old");
            }
        }

        ServerLevel serverWorld = getServerWorld(client);
        if (serverWorld != null) {
            ServerChunkCache scm  = serverWorld.getChunkSource();
            ChunkGenerator   gen  = scm.getGenerator();
            RandomState      nc   = scm.randomState();
            gen.addDebugScreenInfo(list, nc, blockPos);
            Climate.Sampler sampler = nc.sampler();
            BiomeSource biomeSource = gen.getBiomeSource();
            biomeSource.addDebugInfo(list, blockPos, sampler);
            NaturalSpawner.SpawnState spawnInfo = scm.getLastSpawnState();
            if (spawnInfo != null) {
                Object2IntMap<MobCategory> counts = spawnInfo.getMobCategoryCounts();
                list.add("SC: " + spawnInfo.getSpawnableChunkCount() + ", "
                        + Stream.of(MobCategory.values())
                               .map(g -> Character.toUpperCase(g.getName().charAt(0)) + ": " + counts.getInt(g))
                               .collect(Collectors.joining(", ")));
            } else {
                list.add("SC: N/A");
            }
        }

        Identifier postId = getPostProcessorId(client);
        if (postId != null) list.add("Post: " + postId);

        list.add(getSoundDebugString(client)
                + String.format(Locale.ROOT, " (Mood %d%%)",
                        Math.round(getMoodPercentage(client) * 100.0F)));

        return list;
    }

    // Right panel
    public static List<String> getRightText(DebugHudContext ctx) {
        Minecraft client = ctx.client();
        long maxMem   = Runtime.getRuntime().maxMemory();
        long totalMem = Runtime.getRuntime().totalMemory();
        long freeMem  = Runtime.getRuntime().freeMemory();
        long usedMem  = totalMem - freeMem;

        GpuDevice gpu = RenderSystem.getDevice();

        List<String> list = Lists.newArrayList(
                String.format(Locale.ROOT, "Java: %s", System.getProperty("java.version")),
                String.format(Locale.ROOT, "Mem: %2d%% %03d/%03dMB",
                        usedMem * 100L / maxMem, toMiB(usedMem), toMiB(maxMem)),
                String.format(Locale.ROOT, "Allocation rate: %03dMB/s",
                        toMiB(ctx.allocationRate())),
                String.format(Locale.ROOT, "Allocated: %2d%% %03dMB",
                        totalMem * 100L / maxMem, toMiB(totalMem)),
                "",
                String.format(Locale.ROOT, "CPU: %s", getCpuInfo()),
                "",
                String.format(Locale.ROOT, "Display: %dx%d (%s)",
                        client.getWindow().getWidth(),
                        client.getWindow().getHeight(),
                        getGpuVendor(gpu)),
                getGpuRenderer(gpu),
                String.format(Locale.ROOT, "%s %s", getGpuBackend(gpu), getGpuVersion(gpu))
        );

        if (client.showOnlyReducedInfo()) return list;

        if (ctx.blockHit().getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            BlockPos bp         = ((BlockHitResult) ctx.blockHit()).getBlockPos();
            BlockState bs       = client.level.getBlockState(bp);
            list.add("");
            list.add(ChatFormatting.UNDERLINE + "Targeted Block: " + bp.getX() + ", " + bp.getY() + ", " + bp.getZ());
            list.add(String.valueOf(BuiltInRegistries.BLOCK.getKey(bs.getBlock())));
            bs.getValues().forEach(e -> list.add(propertyToString(e.property(), e.value())));
            bs.getBlock().builtInRegistryHolder().tags().map(t -> "#" + t.location()).forEach(list::add);
        }

        if (ctx.fluidHit().getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            BlockPos fp    = ((BlockHitResult) ctx.fluidHit()).getBlockPos();
            FluidState fs  = client.level.getFluidState(fp);
            list.add("");
            list.add(ChatFormatting.UNDERLINE + "Targeted Fluid: " + fp.getX() + ", " + fp.getY() + ", " + fp.getZ());
            list.add(String.valueOf(BuiltInRegistries.FLUID.getKey(fs.getType())));
            fs.getValues().forEach(e -> list.add(propertyToString(e.property(), e.value())));
            fs.getType().builtInRegistryHolder().tags().map(t -> "#" + t.location()).forEach(list::add);
        }

        Entity targeted = client.crosshairPickEntity;
        if (targeted != null) {
            list.add("");
            list.add(ChatFormatting.UNDERLINE + "Targeted Entity");
            list.add(String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(targeted.getType())));
        }

        return list;
    }

    // Private helpers
    @Nullable
    private static ServerLevel getServerWorld(Minecraft client) {
        IntegratedServer server = client.getSingleplayerServer();
        return server != null ? server.getLevel(client.level.dimension()) : null;
    }

    @Nullable
    private static String getServerWorldDebugString(Minecraft client) {
        ServerLevel sw = getServerWorld(client);
        return sw != null ? sw.toString() : null;
    }

    private static Level getWorld(Minecraft client) {
        return DataFixUtils.orElse(
                Optional.ofNullable(client.getSingleplayerServer())
                        .flatMap(s -> Optional.ofNullable(s.getLevel(client.level.dimension()))),
                client.level);
    }

    private static String buildFpsString(Minecraft client) {
        int fps       = getFps(client);
        int maxFps    = getMaxFps(client);
        boolean vsync = isVsync(client);
        String graphics     = getGraphicsMode(client);
        int biomeBlend      = getBiomeBlendRadius(client);

        StringBuilder sb = new StringBuilder();
        sb.append(fps).append(" fps");
        sb.append(" T: ").append(maxFps > 0 && maxFps < 260 ? maxFps : "inf");
        if (vsync) sb.append(" vsync");
        sb.append(" ").append(graphics);
        sb.append(" B: ").append(biomeBlend);
        return sb.toString();
    }

    private static String getBiomeString(Holder<Biome> biome) {
        return biome.unwrap().map(
                k -> k.identifier().toString(),
                b -> "[unregistered " + b + "]"
        );
    }

    private static String propertyToString(Property<?> prop, Comparable<?> val) {
        String s = Util.getPropertyName(prop, val);
        if (Boolean.TRUE.equals(val))  s = ChatFormatting.GREEN + s;
        else if (Boolean.FALSE.equals(val)) s = ChatFormatting.RED + s;
        return prop.getName() + ": " + s;
    }

    private static long toMiB(long bytes) {
        return bytes / 1024L / 1024L;
    }

    // FPS / render options
    private static int getFps(Minecraft client) {
        return client.getFps();
    }

    private static int getMaxFps(Minecraft client) {
        return client.options.framerateLimit().get();
    }

    private static boolean isVsync(Minecraft client) {
        return client.options.enableVsync().get();
    }

    private static String getGraphicsMode(Minecraft client) {
        return client.options.graphicsPreset().get().toString();
    }

    private static int getBiomeBlendRadius(Minecraft client) {
        return client.options.biomeBlendRadius().get();
    }

    // World renderer / managers
    private static String getChunksDebugString(Minecraft client) {
        return client.level.gatherChunkSourceStats();
    }

    private static String getEntitiesDebugString(Minecraft client) {
        return client.levelRenderer.getEntityStatistics();
    }

    private static String getParticleDebugString(Minecraft client) {
        return client.particleEngine.countParticles();
    }

    private static String getSoundDebugString(Minecraft client) {
        return client.getSoundManager().getChannelDebugString();
    }

    private static float getMoodPercentage(Minecraft client) {
        return client.player.getCurrentMood();
    }

    @Nullable
    private static Identifier getPostProcessorId(Minecraft client) {
        return client.gameRenderer.currentPostEffect();
    }

    // Network / tick timing
    private static float getAveragePacketsSent(Connection connection) {
        return connection.getAverageSentPackets();
    }

    private static float getAveragePacketsReceived(Connection connection) {
        return connection.getAverageReceivedPackets();
    }

    private static String getNetworkBrand(ClientPacketListener handler) {
        return handler.serverBrand();
    }

    private static float getMillisPerTick(TickRateManager tickManager) {
        return tickManager.millisecondsPerTick();
    }

    private static float getServerAvgTickTime(IntegratedServer server) {
        return server.getAverageTickTimeNanos() / 1_000_000.0f;
    }

    // GPU
    private static String getCpuInfo() {
        return GLX._getCpuInfo();
    }

    private static String getGpuVendor(GpuDevice gpu) {
        return gpu.getVendor();
    }

    private static String getGpuRenderer(GpuDevice gpu) {
        return gpu.getRenderer();
    }

    private static String getGpuBackend(GpuDevice gpu) {
        return gpu.getBackendName();
    }

    private static String getGpuVersion(GpuDevice gpu) {
        return gpu.getVersion();
    }
}
