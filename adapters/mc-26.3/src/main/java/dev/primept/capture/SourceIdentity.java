package dev.primept.capture;

/** Actual world object identity carried by Minecraft's extracted render state. */
public interface SourceIdentity {
    Object primept$source();
    void primept$source(Object source);
}
