package com.mrpotato.oldf3debugscreen.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.gui.components.debugchart.BandwidthDebugChart;
import net.minecraft.client.gui.components.debugchart.FpsDebugChart;
import net.minecraft.client.gui.components.debugchart.PingDebugChart;
import net.minecraft.client.gui.components.debugchart.ProfilerPieChart;
import net.minecraft.client.gui.components.debugchart.TpsDebugChart;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.HitResult;
import com.mrpotato.oldf3debugscreen.client.debug.AllocationRateCalculator;
import com.mrpotato.oldf3debugscreen.client.debug.DebugHudContext;
import com.mrpotato.oldf3debugscreen.client.debug.OldDebugHudProvider;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Objects;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugHudMixin {

    @Shadow @Final private Minecraft minecraft;

    @Shadow @Nullable private ChunkPos lastPos;
    @Shadow public abstract void clearChunkCache();

    @Shadow @Final private FpsDebugChart fpsChart;
    @Shadow @Final private TpsDebugChart tpsChart;
    @Shadow @Final private PingDebugChart pingChart;
    @Shadow @Final private BandwidthDebugChart bandwidthChart;
    @Shadow @Final private ProfilerPieChart profilerPieChart;
    @Shadow @Final private LocalSampleLogger tickTimeLogger;

    @Shadow public abstract boolean showDebugScreen();
    @Shadow public abstract boolean showProfilerChart();
    @Shadow public abstract boolean showFpsCharts();
    @Shadow public abstract boolean showNetworkCharts();
    @Shadow protected abstract void extractLines(GuiGraphicsExtractor context, List<String> lines, boolean left);

    @Unique
    private final AllocationRateCalculator oldf3screen$allocCalc = new AllocationRateCalculator();

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void oldf3screen$render(GuiGraphicsExtractor context, CallbackInfo ci) {
        ci.cancel();

        if (!minecraft.isGameLoadFinished()) return;
        if (minecraft.options.hideGui && minecraft.screen == null) return;

        boolean showF3 = minecraft.debugEntries.isOverlayVisible();
        boolean showCharts = showProfilerChart() || showFpsCharts() || showNetworkCharts();

        if (!showF3 && !showCharts) return;

        context.nextStratum();

        if (showF3 && minecraft.getCameraEntity() != null && minecraft.level != null) {
            oldf3screen$renderDebugText(context);
        }

        if (showCharts) {
            context.nextStratum();
            oldf3screen$renderCharts(context);
        }
    }

    @Unique
    private void oldf3screen$renderDebugText(GuiGraphicsExtractor context) {
        Entity entity = minecraft.getCameraEntity();

        if (!minecraft.showOnlyReducedInfo()) {
            BlockPos blockPos = entity.blockPosition();
            ChunkPos chunkPos = ChunkPos.containing(blockPos);
            if (!Objects.equals(this.lastPos, chunkPos)) {
                this.lastPos = chunkPos;
                clearChunkCache();
            }
        }

        HitResult blockHit = entity.pick(20.0, 0.0F, false);
        HitResult fluidHit = entity.pick(20.0, 0.0F, true);

        long totalMem = Runtime.getRuntime().totalMemory();
        long freeMem  = Runtime.getRuntime().freeMemory();
        long allocRate = oldf3screen$allocCalc.get(totalMem - freeMem);

        LevelChunk clientChunk = null;
        LevelChunk serverChunk = null;
        if (!minecraft.showOnlyReducedInfo() && this.lastPos != null) {
            clientChunk = minecraft.level.getChunk(this.lastPos.x(), this.lastPos.z());
            IntegratedServer server = minecraft.getSingleplayerServer();
            if (server != null) {
                ServerLevel sw = server.getLevel(minecraft.level.dimension());
                if (sw != null) {
                    ChunkResult<ChunkAccess> result = sw.getChunkSource()
                            .getChunkFuture(this.lastPos.x(), this.lastPos.z(), ChunkStatus.FULL, false)
                            .getNow(null);
                    if (result != null) {
                        ChunkAccess chunk = result.orElse(null);
                        if (chunk instanceof LevelChunk levelChunk) {
                            serverChunk = levelChunk;
                        }
                    }
                }
            }
        }

        DebugHudContext ctx = new DebugHudContext(
                minecraft, blockHit, fluidHit,
                this.lastPos,
                clientChunk,
                serverChunk,
                allocRate);

        List<String> leftText  = OldDebugHudProvider.getLeftText(ctx);
        List<String> rightText = OldDebugHudProvider.getRightText(ctx);

        leftText.add("");
        boolean hasServer = minecraft.getSingleplayerServer() != null;
        leftText.add("Debug charts: [F3+1] Profiler " + (showProfilerChart() ? "visible" : "hidden")
                + "; [F3+2] " + (hasServer ? "FPS + TPS " : "FPS ")
                + (showFpsCharts() ? "visible" : "hidden")
                + "; [F3+3] " + (!minecraft.isLocalServer() ? "Bandwidth + Ping" : "Ping")
                + (showNetworkCharts() ? " visible" : " hidden"));
        leftText.add("For help: press F3 + Q");

        extractLines(context, leftText,  true);
        extractLines(context, rightText, false);
    }


    @Unique
    private void oldf3screen$renderCharts(GuiGraphicsExtractor context) {
        profilerPieChart.setBottomOffset(10);

        if (showFpsCharts()) {
            int w    = context.guiWidth();
            int half = w / 2;
            fpsChart.extractRenderState(context, 0, fpsChart.getWidth(half));
            if (tickTimeLogger.size() > 0) {
                int tw = tpsChart.getWidth(half);
                tpsChart.extractRenderState(context, w - tw, tw);
            }
            profilerPieChart.setBottomOffset(tpsChart.getFullHeight());
        }

        if (showNetworkCharts() && minecraft.getConnection() != null) {
            int w    = context.guiWidth();
            int half = w / 2;
            if (!minecraft.isLocalServer()) {
                bandwidthChart.extractRenderState(context, 0, bandwidthChart.getWidth(half));
            }
            int pw = pingChart.getWidth(half);
            pingChart.extractRenderState(context, w - pw, pw);
            profilerPieChart.setBottomOffset(pingChart.getFullHeight());
        }

        if (showProfilerChart()) {
            profilerPieChart.extractRenderState(context);
        }
    }
}
