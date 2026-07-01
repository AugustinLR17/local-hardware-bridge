package io.github.augustinlr17.localhardwarebridge.services;

import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that {@link ConfigService} is safe under concurrent access.
 * The config field uses an AtomicReference so reads and writes from multiple
 * threads never corrupt the reference or return a partially-published object.
 */
public class ConfigServiceConcurrencyTest {

    @Test
    public void concurrentLoadFromJsonIsSafe() throws Exception {
        ConfigService service = ConfigService.getInstance();
        int threads = 8;
        int iterations = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int offset = i;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < iterations; j++) {
                        Config cfg = new Config();
                        cfg.getServer().setPort(10000 + offset * 100 + j);
                        com.fasterxml.jackson.databind.ObjectMapper mapper =
                                new com.fasterxml.jackson.databind.ObjectMapper();
                        service.loadFromJson(mapper.writeValueAsString(cfg));
                        Config read = service.getConfig();
                        assertNotNull(read);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue("Concurrent load did not finish in time", done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(0, errors.get());
    }

    @Test
    public void concurrentReadsReturnConsistentConfig() throws Exception {
        ConfigService service = ConfigService.getInstance();
        Config cfg = new Config();
        cfg.getServer().setPort(99999);
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        service.loadFromJson(mapper.writeValueAsString(cfg));

        int readers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(readers);
        CountDownLatch start = new CountDownLatch(1);
        List<Config> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> exceptions = new ArrayList<>();

        for (int i = 0; i < readers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(service.getConfig());
                } catch (Throwable t) {
                    synchronized (exceptions) {
                        exceptions.add(t);
                    }
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertTrue("No exceptions expected", exceptions.isEmpty());
        assertEquals(readers, results.size());
        for (Config c : results) {
            assertNotNull(c);
            assertEquals(99999, c.getServer().getPort());
        }
    }
}
