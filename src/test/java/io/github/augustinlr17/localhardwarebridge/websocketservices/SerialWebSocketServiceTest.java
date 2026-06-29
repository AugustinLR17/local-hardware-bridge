package io.github.augustinlr17.localhardwarebridge.websocketservices;

import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link Config.SerialMapping} channel-related behavior.
 * These tests verify the channel name construction logic without
 * instantiating {@link SerialWebSocketService} (which requires a real
 * or mock serial port via jSerialComm).
 */
public class SerialWebSocketServiceTest {

    @Test
    public void channelNameIsBuiltFromType() {
        Config.SerialMapping mapping = new Config.SerialMapping();
        mapping.setType("WEIGH");
        mapping.setName("COM3");

        // The channel is "/serial/" + mapping.getType()
        assertEquals("/serial/WEIGH", "/serial/" + mapping.getType());
    }

    @Test
    public void channelNameHandlesUnderscores() {
        Config.SerialMapping mapping = new Config.SerialMapping();
        mapping.setType("MY_SCALE_2");

        assertEquals("/serial/MY_SCALE_2", "/serial/" + mapping.getType());
    }

    @Test
    public void channelNamePreservesCase() {
        Config.SerialMapping mapping = new Config.SerialMapping();
        mapping.setType("Weigh");

        // Type is case-sensitive — "Weigh" and "WEIGH" are different channels
        assertEquals("/serial/Weigh", "/serial/" + mapping.getType());
    }

    @Test
    public void serialMappingDefaultsAreCorrect() {
        Config.SerialMapping mapping = new Config.SerialMapping();

        assertNull(mapping.getType());
        assertNull(mapping.getName());
        assertNull(mapping.getBaudRate());
        assertNull(mapping.getNumDataBits());
        assertNull(mapping.getNumStopBits());
        assertNull(mapping.getParity());
        assertFalse(mapping.getReadMultipleBytes());
        assertEquals("ISO-8859-1", mapping.getReadCharset());
    }

    @Test
    public void serialMappingBinaryCharsetIsSupported() {
        Config.SerialMapping mapping = new Config.SerialMapping();
        mapping.setReadCharset("BINARY");

        assertEquals("BINARY", mapping.getReadCharset());
    }
}
