package com.lms.outbox.controller;

import com.lms.common.page.PageQuery;
import com.lms.common.page.PageResponse;
import com.lms.outbox.dto.OutboxEventResponse;
import com.lms.outbox.model.OutboxStatus;
import com.lms.outbox.service.OutboxRelay;
import com.lms.outbox.service.OutboxStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Operational view of the outbox: what has happened, what has not been delivered, and what gave up.
 *
 * <p>Admins only. The payloads contain learners' email addresses.
 */
@Tag(name = "Events")
@RestController
@RequestMapping("/api/v1/events")
@PreAuthorize("hasRole('ADMIN')")
public class OutboxController {

    private final OutboxStore store;
    private final OutboxRelay relay;

    public OutboxController(OutboxStore store, OutboxRelay relay) {
        this.store = store;
        this.relay = relay;
    }

    @Operation(summary = "Events the system has recorded, newest first")
    @GetMapping
    public PageResponse<OutboxEventResponse> list(@RequestParam(required = false) OutboxStatus status,
                                                  @RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        return PageResponse.of(store.list(status, query.toPageable(Sort.unsorted())),
                OutboxEventResponse::from);
    }

    @Operation(summary = "Put a dead-lettered event back in the queue")
    @PostMapping("/{eventId}/requeue")
    public OutboxEventResponse requeue(@PathVariable UUID eventId) {
        store.requeue(eventId);
        return OutboxEventResponse.from(store.require(eventId));
    }

    @Operation(summary = "Run a delivery pass now rather than waiting for the next tick")
    @PostMapping("/deliver")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void deliverNow() {
        relay.deliverDue();
    }
}
