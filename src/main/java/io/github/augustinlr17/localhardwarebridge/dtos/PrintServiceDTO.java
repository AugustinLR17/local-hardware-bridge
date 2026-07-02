package io.github.augustinlr17.localhardwarebridge.dtos;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class PrintServiceDTO {
    private String name;
    private String description;
    private boolean acceptingJobs;
    private String state;

    /** Backward-compatible constructor for callers that don't probe state. */
    public PrintServiceDTO(String name, String description) {
        this.name = name;
        this.description = description;
        this.acceptingJobs = true;
        this.state = "idle";
    }

    public PrintServiceDTO(String name, String description, boolean acceptingJobs, String state) {
        this.name = name;
        this.description = description;
        this.acceptingJobs = acceptingJobs;
        this.state = state;
    }
}
