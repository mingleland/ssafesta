package com.example.ssafesta.common;

/**
 * One request field, and whether the client actually sent the key.
 *
 * <p>A {@code record} cannot carry this. {@code {}} and {@code {"videoUrl": null}} both arrive as
 * {@code null}, so "leave it alone" and "clear it" become the same request — and a client that
 * forgot a field silently deletes a saved value. 016 shipped that collapse once (T-97).
 *
 * <p>Jackson calls a setter only when the key is present, so {@code set} being called <i>at all</i>
 * is the signal. 016·009·007 all need it, which is why it is here rather than a third private copy.
 *
 * <p>Generic because 007 patches an {@code Integer}, a {@code Boolean} and a {@code List<String>}
 * alongside the strings.
 */
public final class PresenceField<T> {

    private T value;
    private boolean present;

    /** Call from the {@code @JsonProperty} setter — being called is the presence signal. */
    public void set(T value) {
        this.value = value;
        this.present = true;
    }

    /** {@code true} when the key was in the body, whatever its value. */
    public boolean isPresent() {
        return present;
    }

    /** {@code null} when the client sent an explicit {@code null}, or when the key was absent. */
    public T value() {
        return value;
    }
}
