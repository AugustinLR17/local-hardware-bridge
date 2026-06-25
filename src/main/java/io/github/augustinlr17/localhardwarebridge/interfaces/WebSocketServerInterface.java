package io.github.augustinlr17.localhardwarebridge.interfaces;


public interface WebSocketServerInterface {
    void messageToServer(String channel, String message);

    void messageToServer(String channel, byte[] message);

    void messageToService(String channel, String message);

    void messageToService(String channel, byte[] message);

    void registerService(WebSocketServiceInterface service);

    void unregisterService(WebSocketServiceInterface service);
}
