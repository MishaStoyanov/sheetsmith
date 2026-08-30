package com.ap0stole.sheetsmith.services;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One lock per session, taken by everything that appends to a session's revision chain.
 * <p>
 * Both writers — a chat turn and an improve job — read the current revision and write the next one.
 * Without a common lock the two would derive the same "next" number and one edit would be lost.
 * <p>
 * Lock order: the improve job takes this lock <em>after</em> the job semaphore, never before. A job
 * holding a slot may wait for a session, but a job holding a session must never wait for a slot —
 * that would deadlock against another job that holds the only slot and wants the same session.
 * Chat turns never touch the semaphore, so there is no other cycle to worry about.
 */
@Component
public class SessionLockRegistry {

    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Runs the work with this session's lock held, and releases it either way.
     * <p>
     * The only way in. Handing a locked lock back to a caller and trusting them to release it in a
     * finally worked — both callers did — but it is a rule that lives in prose, and the next caller
     * reads the prose only if they think to. Here the release is the method's own job.
     */
    public <T, E extends Exception> T withSession(String sessionId, Work<T, E> work) throws E {
        ReentrantLock lock = locks.computeIfAbsent(sessionId, id -> new ReentrantLock());
        lock.lock();
        try {
            return work.run();
        } finally {
            lock.unlock();
        }
    }

    /** Work that may throw whatever its caller throws — an upload reads files, and files fail. */
    @FunctionalInterface
    public interface Work<T, E extends Exception> {

        T run() throws E;
    }
}
