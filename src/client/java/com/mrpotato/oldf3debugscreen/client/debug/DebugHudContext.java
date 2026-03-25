package com.mrpotato.oldf3debugscreen.client.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Snapshot of DebugHud state for one frame, built by DebugHudMixin and consumed by OldDebugHudProvider.
 *
 * chunkPos / clientChunk / serverChunk are null when hasReducedDebugInfo() is true
 * (the reduced path doesn't need chunk data).
 * allocationRate is 0 for the left-text context where it is unused.
 */
public record DebugHudContext(
        Minecraft client,
        HitResult blockHit,
        HitResult fluidHit,
        @Nullable ChunkPos chunkPos,
        @Nullable LevelChunk clientChunk,
        @Nullable LevelChunk serverChunk,
        long allocationRate
) {}
