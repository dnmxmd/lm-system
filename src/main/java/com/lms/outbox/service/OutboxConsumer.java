package com.lms.outbox.service;

/**
 * Something that wants durable delivery of an event.
 *
 * <p>Consumers are called by the relay, after the originating transaction has committed and
 * outside any transaction of its own, so they may safely talk to the outside world. A consumer
 * that throws gets the event again later; one that returns is never given it twice.
 */
public interface OutboxConsumer {

    boolean supports(String eventType);

    /**
     * @param event the deserialized event. Consumers match on its type, typically with a
     *              pattern-matching switch.
     */
    void consume(Object event);
}
