// TDLib JNI entry points. Interface: tdlib/td, Boost Software License 1.0.
package org.drinkless.tdlib;
public final class JsonClient {
    static { System.loadLibrary("tdjsonjava"); }
    public static native int createClientId();
    public static native void send(int clientId, String request);
    public static native String receive(double timeout);
    public static native String execute(String request);
    public interface LogMessageHandler {
        void onLogMessage(int verbosityLevel, String message);
    }
    public static native void setLogMessageHandler(int maxVerbosityLevel, LogMessageHandler handler);
    private JsonClient() {}
}
