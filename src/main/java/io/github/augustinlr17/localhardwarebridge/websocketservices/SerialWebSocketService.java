package io.github.augustinlr17.localhardwarebridge.websocketservices;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.codec.binary.Hex;
import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import io.github.augustinlr17.localhardwarebridge.dtos.NotificationDTO;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServerInterface;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServiceInterface;
import io.github.augustinlr17.localhardwarebridge.utils.ThreadUtil;

import java.nio.charset.Charset;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Log4j2
public class SerialWebSocketService implements WebSocketServiceInterface {
    private WebSocketServerInterface server;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Config.SerialMapping mapping;
    private final SerialPort serialPort;
    private final BlockingQueue<byte[]> writeQueue = new LinkedBlockingQueue<>();

    private Thread readThread;
    private Thread writeThread;
    private Thread monitorThread;

    private volatile boolean isRunning = true;

    private static final String BINARY = "BINARY";

    public SerialWebSocketService(Config.SerialMapping newMapping) {
        log.info("Starting SerialWebSocketService on {}", newMapping.getName());

        this.mapping = newMapping;

        this.serialPort = SerialPort.getCommPort(newMapping.getName());

        if (mapping.getBaudRate() != null) serialPort.setBaudRate(mapping.getBaudRate());
        if (mapping.getNumDataBits() != null) serialPort.setNumDataBits(mapping.getNumDataBits());
        if (mapping.getNumStopBits() != null) serialPort.setNumStopBits(mapping.getNumStopBits());
        if (mapping.getParity() != null) serialPort.setParity(mapping.getParity());
    }

    @Override
    public void start() {
        isRunning = true;

        readThread = new Thread(() -> {
            log.debug("Serial Read Thread started for {}", mapping.getName());

            while (isRunning) {
                if (serialPort.isOpen()) {
                    int bytesAvailable = serialPort.bytesAvailable();
                    if (bytesAvailable == 0) {
                        // No data coming from COM portName
                        ThreadUtil.silentSleep(10);
                        continue;
                    } else if (bytesAvailable == -1) {
                        // Check if portName closed unexpected (e.g. Unplugged)
                        serialPort.closePort();

                        try {
                            server.messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("WARNING", "Serial Port", "Serial " + mapping.getName() + "(" + mapping.getType() + ") unplugged")));
                        } catch (JsonProcessingException e) {
                            log.error("Failed to send notification: {}", e.getMessage());
                        }

                        log.warn("Serial {} unplugged", mapping.getName());

                        continue;
                    }

                    int bytesToRead = Boolean.TRUE.equals(mapping.getReadMultipleBytes()) ? bytesAvailable : 1;

                    byte[] receivedData = new byte[bytesToRead];
                    serialPort.readBytes(receivedData, bytesToRead);

                    if (server != null) {
                        if (Objects.equals(mapping.getReadCharset(), BINARY)) server.messageToServer(getChannel(), receivedData);
                        else server.messageToServer(getChannel(), new String(receivedData, Charset.forName(mapping.getReadCharset())));
                    }
                } else {
                    // Port closed (e.g. unplugged / awaiting reconnect): avoid busy-spinning.
                    ThreadUtil.silentSleep(100);
                }
            }

            log.debug("Serial Read Thread stopped for {}", mapping.getName());
        }, "serial-read-" + mapping.getName());
        readThread.setDaemon(true);

        writeThread = new Thread(() -> {
            log.debug("Serial Write Thread started for {}", mapping.getName());

            while (isRunning) {
                if (serialPort.isOpen()) {
                    // Drain everything queued since the last cycle (no last-write-wins loss).
                    // Use blocking poll with timeout so the thread wakes immediately when
                    // data is available instead of sleeping a fixed 10ms — reduces write
                    // latency from up to 10ms to near-zero.
                    byte[] data;
                    try {
                        data = writeQueue.poll(10, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    while (data != null) {
                        if (data.length > 0) {
                            log.trace("Bytes: {}", Hex.encodeHexString(data));
                            serialPort.writeBytes(data, data.length);
                        }
                        data = writeQueue.poll(); // drain remaining without waiting
                    }
                } else {
                    // Port closed: avoid busy-spinning at 100% CPU.
                    ThreadUtil.silentSleep(100);
                }
            }

            log.debug("Serial Write Thread stopped for {}", mapping.getName());
        }, "serial-write-" + mapping.getName());
        writeThread.setDaemon(true);

        monitorThread = new Thread(() -> {
            log.debug("Serial Monitor Thread started for {}", mapping.getName());

            while (isRunning) {
                if (serialPort.isOpen()) {
                    ThreadUtil.silentSleep(1000);
                } else {
                    log.info("Trying to connect to serial @ {}", serialPort.getSystemPortName());
                    serialPort.openPort(1000);

                    if (serialPort.isOpen()) {
                        log.info("Serial {} is now open", mapping.getName());
                    }
                }
            }

            log.debug("Serial Monitor Thread stopped for {}", mapping.getName());
        }, "serial-monitor-" + mapping.getName());
        monitorThread.setDaemon(true);

        readThread.start();
        writeThread.start();
        monitorThread.start();
    }

    @Override
    public void stop() {
        log.info("Stopping SerialWebSocketService");

        isRunning = false;

        readThread.interrupt();
        writeThread.interrupt();
        monitorThread.interrupt();

        // Wait for threads to actually terminate so they don't race with a
        // subsequent start() (which would reuse the same SerialPort handle).
        try {
            readThread.join(2000);
            writeThread.join(2000);
            monitorThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for serial threads to stop");
        }

        serialPort.closePort();

        log.info("Stopped SerialWebSocketService");
    }

    @Override
    public void messageToService(String message) {
        messageToService(message.getBytes());
    }

    @Override
    public void messageToService(byte[] message) {
        if (message != null) {
            if (!writeQueue.offer(message)) {
                log.warn("Serial write queue full, message dropped for channel {}", getChannel());
            }
        }
    }

    @Override
    public void onRegister(WebSocketServerInterface newServer) {
        this.server = newServer;
    }

    @Override
    public void onUnregister() {
        this.server = null;
    }

    @Override
    public String getChannel() {
        return "/serial/" + mapping.getType();
    }
}
