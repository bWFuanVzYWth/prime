package dev.primept.capture;

/** Identity-owned world context; never uses a mod object's equals/hashCode implementation. */
public interface ModelSourceOwner {
    ModelCapture.Source primept$modelSource();
    void primept$modelSource(ModelCapture.Source context);
}
