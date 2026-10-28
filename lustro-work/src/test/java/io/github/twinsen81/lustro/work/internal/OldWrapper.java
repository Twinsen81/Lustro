package io.github.twinsen81.lustro.work.internal;

/** A wrapper written for the older interface, which had only onOld. */
public abstract class OldWrapper implements NewerListener {
    @Override
    public void onOld() {}
}
