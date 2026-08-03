package io.github.augustinlr17.localhardwarebridge.dtos;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Unit tests for the {@link Config.PrintJobs} section: exact default values
 * from the 2.5.0 architecture and Jackson round-trip.
 * Fully hermetic.
 */
public class ConfigPrintJobsTest {

    private static ObjectMapper appMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    public void defaultsMatchArchitectureSpec() {
        Config.PrintJobs pj = new Config.PrintJobs();

        // Capacity defaults (exact values from VAL-CROSS-006 / architecture).
        assertEquals(10485760, pj.getMaxPayloadBytes());
        assertEquals(1000, pj.getMaxQueuedJobs());
        assertEquals(524288000, pj.getMaxPersistentBytes());
        assertEquals(419430400, pj.getCleanupThresholdBytes());
        assertEquals(367001600, pj.getCleanupTargetBytes());
        assertEquals(268435456, pj.getMinFreeBytes());
        assertEquals(5, pj.getMinFreePercent());
        assertEquals(7, pj.getSuccessRetentionDays());
        assertEquals(30, pj.getFailureRetentionDays());

        // Retry defaults (conservative, positive, bounded).
        assertEquals(30, pj.getInitialRetryDelaySeconds());
        assertEquals(3600, pj.getMaxRetryDelaySeconds());
        assertEquals(10, pj.getMaxAttempts());
        assertEquals(72, pj.getMaxRetryAgeHours());
        assertEquals(2, pj.getRetryWorkers());
    }

    @Test
    public void sectionPresentInDefaultConfig() {
        Config config = new Config();
        assertNotNull(config.getPrintJobs());
        assertEquals(10485760, config.getPrintJobs().getMaxPayloadBytes());
        assertEquals(1000, config.getPrintJobs().getMaxQueuedJobs());
    }

    @Test
    public void roundTripPreservesAllFields() throws Exception {
        ObjectMapper mapper = appMapper();

        Config.PrintJobs original = new Config.PrintJobs();
        original.setMaxPayloadBytes(5242880);
        original.setMaxQueuedJobs(500);
        original.setMaxPersistentBytes(262144000);
        original.setCleanupThresholdBytes(209715200);
        original.setCleanupTargetBytes(183500800);
        original.setMinFreeBytes(134217728);
        original.setMinFreePercent(10);
        original.setSuccessRetentionDays(14);
        original.setFailureRetentionDays(60);
        original.setInitialRetryDelaySeconds(15);
        original.setMaxRetryDelaySeconds(1800);
        original.setMaxAttempts(5);
        original.setMaxRetryAgeHours(48);
        original.setRetryWorkers(4);

        String json = mapper.writeValueAsString(original);
        Config.PrintJobs restored = mapper.readValue(json, Config.PrintJobs.class);

        assertEquals(original, restored);
    }

    @Test
    public void fullConfigRoundTripPreservesPrintJobs() throws Exception {
        ObjectMapper mapper = appMapper();

        Config original = new Config();
        original.getPrintJobs().setMaxPayloadBytes(2097152);
        original.getPrintJobs().setMaxAttempts(3);
        original.getPrintJobs().setRetryWorkers(1);

        String json = mapper.writeValueAsString(original);
        Config restored = mapper.readValue(json, Config.class);

        assertEquals(2097152, restored.getPrintJobs().getMaxPayloadBytes());
        assertEquals(3, restored.getPrintJobs().getMaxAttempts());
        assertEquals(1, restored.getPrintJobs().getRetryWorkers());
        // Untouched fields keep defaults.
        assertEquals(1000, restored.getPrintJobs().getMaxQueuedJobs());
        assertEquals(524288000, restored.getPrintJobs().getMaxPersistentBytes());
    }
}
