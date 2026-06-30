package io.github.augustinlr17.localhardwarebridge.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.ToString;
import io.github.augustinlr17.localhardwarebridge.utils.AnnotatedPrintable;

import java.util.ArrayList;
import java.util.UUID;

@ToString
@Getter
public class PrintDocument {
    String type;
    String url;
    String id;
    UUID uuid = UUID.randomUUID();
    Integer qty = 1;
    @JsonProperty("file_content") String fileContent;
    @JsonProperty("raw_content") String rawContent;
    @JsonProperty("paper_tray") String paperTray;
    ArrayList<AnnotatedPrintable.AnnotatedPrintableAnnotation> extras = new ArrayList<>();

    /** Recto-verso (true) or recto seul (false). Null = printer default. */
    Boolean duplex;

    /** Couleur (true) or noir et blanc (false). Null = printer default. */
    Boolean color;
}