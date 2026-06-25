package io.github.augustinlr17.localhardwarebridge.interfaces;

public interface WebSocketServiceInterface {
    void start();

    void stop();

    void messageToService(String message);

    void messageToService(byte[] message);

    void onRegister(WebSocketServerInterface server);

    void onUnregister();

    String getChannel();
}
