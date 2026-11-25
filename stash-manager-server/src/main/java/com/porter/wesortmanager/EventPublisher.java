package com.porter.wesortmanager;

/**
 * PR3 (#142): after-commit event publication.
 *
 * <p>Replaces the static {@code LiveEvents.bump/routeChanged-on-any-2xx}
 * behavior: implementations publish only after the surrounding
 * {@link SqliteWriteTransaction} commits successfully. Rollback publishes
 * nothing. The default implementation delegates to {@link LiveEvents} but
 * only via {@link #afterCommit}, never from HTTP handling directly.
 */
public interface EventPublisher {
    void afterCommit(String channel, long revision);

    static EventPublisher live() {
        return (channel, revision) -> LiveEvents.bump(channel);
    }

    static EventPublisher noop() {
        return (channel, revision) -> {};
    }
}
