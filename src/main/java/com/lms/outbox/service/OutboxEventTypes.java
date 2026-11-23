package com.lms.outbox.service;

import com.lms.enrollment.event.EnrollmentCompleted;
import com.lms.enrollment.event.EnrollmentCreated;
import com.lms.payment.event.PaymentFailed;
import com.lms.payment.event.PaymentSucceeded;
import com.lms.user.event.UserRegistered;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Which events are delivered durably, and how to read one back.
 *
 * <p>Only events with an out-of-process consequence are here. Analytics and payment creation react
 * inside the originating transaction, where they are already atomic — routing them through the
 * outbox would make counting eventually consistent and undo that, for nothing.
 *
 * <p>The stored name is the simple class name rather than the fully qualified one, so moving a
 * class between packages does not strand rows that are already written.
 */
public final class OutboxEventTypes {

    private static final Map<String, Class<?>> BY_NAME = new LinkedHashMap<>();

    static {
        register(UserRegistered.class);
        register(EnrollmentCreated.class);
        register(EnrollmentCompleted.class);
        register(PaymentSucceeded.class);
        register(PaymentFailed.class);
    }

    private OutboxEventTypes() {
    }

    private static void register(Class<?> type) {
        BY_NAME.put(type.getSimpleName(), type);
    }

    public static String nameOf(Object event) {
        return event.getClass().getSimpleName();
    }

    public static boolean isDurable(Object event) {
        return BY_NAME.containsKey(nameOf(event));
    }

    public static Set<String> names() {
        return Set.copyOf(BY_NAME.keySet());
    }

    /**
     * @throws IllegalStateException when a stored row names a type this build no longer knows.
     *                              Better to stop and be told than to silently drop the event.
     */
    public static Class<?> classOf(String name) {
        Class<?> type = BY_NAME.get(name);
        if (type == null) {
            throw new IllegalStateException("No event type is registered under the name " + name);
        }
        return type;
    }
}
