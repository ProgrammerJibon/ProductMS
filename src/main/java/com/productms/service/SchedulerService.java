package com.productms.service;

import com.productms.dao.HeldCartDAO;
import com.productms.dao.ProductDAO;
import com.productms.db.Database;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background jobs, every minute: apply scheduled price changes that are due, discard expired
 * held carts (releasing their reservations) and sync sales queued while the database was down.
 */
public final class SchedulerService {

    private static ScheduledExecutorService exec;
    private static volatile Runnable afterSync;

    private SchedulerService() {
    }

    public static synchronized void start() {
        if (exec != null) {
            return;
        }
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduler");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(SchedulerService::tick, 5, 60, TimeUnit.SECONDS);
    }

    /** Called after an offline-queue sync changed something (to refresh the POS). */
    public static void setAfterSync(Runnable r) {
        afterSync = r;
    }

    /** Runs the jobs now (also used by "Sync now"). */
    public static void tick() {
        if (!Database.isReachable()) {
            return;
        }
        try {
            int n = new ProductDAO().applyDuePriceSchedules();
            if (n > 0) {
                System.out.println("Applied " + n + " scheduled price change(s).");
            }
        } catch (Exception e) {
            System.err.println("Scheduled prices: " + e.getMessage());
        }
        try {
            new HeldCartDAO().purgeExpired();
        } catch (Exception e) {
            System.err.println("Held cart expiry: " + e.getMessage());
        }
        try {
            if (OfflineQueue.syncAll() > 0 && afterSync != null) {
                afterSync.run();
            }
        } catch (Exception e) {
            System.err.println("Offline sync: " + e.getMessage());
        }
    }
}
