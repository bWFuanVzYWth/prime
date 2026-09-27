package dev.primept.render;

/** Session-only intent. A frame boundary commits the change after native retirement succeeds. */
public final class OfflineMode {
    private boolean requested, active;
    public boolean requested() {
        return requested;
    }
    public boolean active() {
        return active;
    }
    public void request(boolean value) {
        requested = value;
    }
    public boolean desired(boolean hasRenderedScene) {
        return requested && hasRenderedScene;
    }
    public void committed(boolean value) {
        active = value;
    }
    public void reset() {
        requested = active = false;
    }

    /** The version adapter resolves the toggle key. Called only for a key press. */
    public boolean shortcut(boolean toggleKey, boolean control, boolean alt) {
        if (!toggleKey || !control || !alt)
            return false;
        requested = !requested;
        return true;
    }
}
