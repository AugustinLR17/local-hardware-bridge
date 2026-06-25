package io.github.augustinlr17.localhardwarebridge.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDTO {
    public String type;
    public String title;
    public String message;
}
