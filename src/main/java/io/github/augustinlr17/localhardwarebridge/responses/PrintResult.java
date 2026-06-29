package io.github.augustinlr17.localhardwarebridge.responses;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@ToString
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PrintResult {
    private Boolean success;
    private String message;
    private String id;
    private String printerName;
}
