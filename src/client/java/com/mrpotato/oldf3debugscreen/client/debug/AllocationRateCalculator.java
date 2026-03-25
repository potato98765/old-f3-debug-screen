package com.mrpotato.oldf3debugscreen.client.debug;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Mirrors the private DebugHud$AllocationRateCalculator inner class.
 * Kept as its own class so we never have to access an inaccessible type via reflection.
 */
public class AllocationRateCalculator {

    private static final long INTERVAL_MS = 500L;
    private static final List<GarbageCollectorMXBean> GC_BEANS =
            ManagementFactory.getGarbageCollectorMXBeans();

    private long lastTime = 0L;
    private long lastAllocBytes = -1L;
    private long lastGcCount = -1L;
    private long allocationRate = 0L;

    /** Returns the smoothed allocation rate in bytes/second. */
    public long get(long currentAllocBytes) {
        long now = System.currentTimeMillis();
        if (now - lastTime < INTERVAL_MS) {
            return allocationRate;
        }
        long gc = gcCount();
        if (lastTime != 0L && gc == lastGcCount) {
            double scale = (double) TimeUnit.SECONDS.toMillis(1L) / (double) (now - lastTime);
            allocationRate = Math.round((currentAllocBytes - lastAllocBytes) * scale);
        }
        lastTime = now;
        lastAllocBytes = currentAllocBytes;
        lastGcCount = gc;
        return allocationRate;
    }

    private static long gcCount() {
        long n = 0L;
        for (GarbageCollectorMXBean b : GC_BEANS) n += b.getCollectionCount();
        return n;
    }
}
