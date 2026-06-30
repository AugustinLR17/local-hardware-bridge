package io.github.augustinlr17.localhardwarebridge.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents a paper tray (input bin) available on a printer.
 * The {@code value} is the string to pass as {@code paper_tray} in a print
 * request; {@code description} is a human-readable label.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PrinterTrayDTO {
    private String value;
    private String description;
}