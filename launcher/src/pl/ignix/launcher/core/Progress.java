package pl.ignix.launcher.core;

/** Progress callback for long jobs. fraction = 0..1, or -1 for "working, unknown". */
public interface Progress {
    void update(String status, double fraction);
}
