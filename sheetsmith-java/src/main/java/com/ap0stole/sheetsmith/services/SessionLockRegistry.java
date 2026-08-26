package com.ap0stole.sheetsmith.services;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

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

    /** Locks the session and hands the lock back; callers must unlock it in a {@code finally} block. */
    public ReentrantLock acquire(String sessionId) {
        ReentrantLock lock = locks.computeIfAbsent(sessionId, id -> new ReentrantLock());
        lock.lock();
        return lock;
    }

    /** The safe form, for work that throws nothing checked. */
    public <T> T withSession(String sessionId, Supplier<T> work) {
        ReentrantLock lock = acquire(sessionId);
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }
}
