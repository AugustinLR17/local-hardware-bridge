module io.github.augustinlr17.localhardwarebridge {
    requires java.desktop;
    requires com.fazecast.jSerialComm;
    requires jdk.management;
    requires org.bouncycastle.provider;
    requires org.bouncycastle.pkix;
    requires org.apache.commons.io;
    requires org.slf4j;
    requires org.apache.pdfbox;
    requires org.apache.commons.codec;
    requires org.apache.httpcomponents.core5.httpcore5;
    requires org.apache.logging.log4j;
    requires io.javalin;
    requires com.fasterxml.jackson.databind;
    requires io.javalin.community.ssl;
    requires static lombok;

    opens io.github.augustinlr17.localhardwarebridge.dtos to com.fasterxml.jackson.databind;
    opens io.github.augustinlr17.localhardwarebridge.responses to com.fasterxml.jackson.databind;
    opens io.github.augustinlr17.localhardwarebridge.utils to com.fasterxml.jackson.databind;

    exports io.github.augustinlr17.localhardwarebridge;
    exports io.github.augustinlr17.localhardwarebridge.interfaces;
    exports tigerworkshop.webapphardwarebridge;
}
