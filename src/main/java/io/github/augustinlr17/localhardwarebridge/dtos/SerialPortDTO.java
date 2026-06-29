package io.github.augustinlr17.localhardwarebridge.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SerialPortDTO {
    private String name;
    private String description;
    private String manufacturer;
}
