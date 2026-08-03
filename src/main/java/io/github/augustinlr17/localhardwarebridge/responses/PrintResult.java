package io.github.augustinlr17.localhardwarebridge.responses;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@ToString
@Getter
@Setter
@NoArgsConstructor
public class PrintResult {
    private Boolean success;
    private String message;
    private String id;
    private String printerName;

    /**
     * Server-assigned stable job identifier, distinct from the existing
     * client-supplied {@code id}. Additive field — null when not set, which
     * keeps older clients that ignore unknown properties fully compatible.
     */
    private String jobId;

    /**
     * Whether the submission was durably queued for retry rather than
     * reaching an immediate terminal result. Additive field — null when
     * not set. A value of {@code true} means a retryable pre-spool failure
     * was queued; {@code false} means an immediate terminal result.
     */
    private Boolean queued;

    /**
     * Existing 4-argument constructor preserved for source compatibility
     * with all existing callers. The additive {@code jobId} and
     * {@code queued} fields are left null.
     */
    public PrintResult(Boolean success, String message, String id, String printerName) {
        this.success = success;
        this.message = message;
        this.id = id;
        this.printerName = printerName;
    }

    /**
     * Full 6-argument constructor including the additive fields.
     */
    public PrintResult(Boolean success, String message, String id, String printerName,
                       String jobId, Boolean queued) {
        this.success = success;
        this.message = message;
        this.id = id;
        this.printerName = printerName;
        this.jobId = jobId;
        this.queued = queued;
    }
}
