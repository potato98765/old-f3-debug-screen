package com.mrpotato.oldf3debugscreen.client.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

public record DebugHudContext(
        Minecraft client,
        HitResult blockHit,
        HitResult fluidHit,
        @Nullable ChunkPos chunkPos,
        @Nullable LevelChunk clientChunk,
        @Nullable LevelChunk serverChunk,
        long allocationRate
) {}
