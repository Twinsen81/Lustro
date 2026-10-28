package io.github.twinsen81.lustro.work.internal;

/**
 * A listener interface as a newer WorkManager could have it: one abstract method and
 * one default method more than {@link OldWrapper} knows. In Java, so that the wrapper
 * gets no compatibility stub for the default method, as a wrapper compiled against
 * the older interface has none.
 */
public interface NewerListener {
    void onOld();

    void onNew();

    default void onNewDefault() {}
}
