package gnoolson.locker;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class OptimisticLocalLocker implements Locker {

    private final ReentrantLock innerLock = new ReentrantLock();
    private final Map<String, XLock> allLockedIds = new HashMap<>();
    private final long minimumWaitTimeBeforeNewLockAttempt;
    private final long maximumWaitTimeBeforeNewLockAttempt;
    private final long maximumLockAttemptTime;

    /*
     *
     *
     * */
    public OptimisticLocalLocker() {
        this(1, 10, 500);
    }

    public OptimisticLocalLocker(long waitingTimeForTry, long maximumLockAttemptTime) {
        this(waitingTimeForTry, waitingTimeForTry, maximumLockAttemptTime);
    }

    public OptimisticLocalLocker(long minimumWaitTimeBeforeNewLockAttempt, long maximumWaitTimeBeforeNewLockAttempt, long maximumLockAttemptTime) {
        if (minimumWaitTimeBeforeNewLockAttempt < 1)
            throw new RuntimeException("The minimum time is less than 1 ms");

        if (maximumWaitTimeBeforeNewLockAttempt < 1)
            throw new RuntimeException("The maximum time is less than 1 ms");

        if (maximumLockAttemptTime < 1)
            throw new RuntimeException("The maximum lock attempt time is less than 1 ms");

        if (minimumWaitTimeBeforeNewLockAttempt > maximumWaitTimeBeforeNewLockAttempt)
            throw new RuntimeException("The minimum time is greater than the maximum time");

        this.minimumWaitTimeBeforeNewLockAttempt = minimumWaitTimeBeforeNewLockAttempt;
        this.maximumWaitTimeBeforeNewLockAttempt = maximumWaitTimeBeforeNewLockAttempt;
        this.maximumLockAttemptTime = maximumLockAttemptTime;
    }

    @Override
    public LockedIds lock(String... keys) {
        boolean retry = false;
        List<XLock> ids = new ArrayList<>(keys.length);
        long totalSleepTime = 0;

        do {
            boolean fail = false;
            if (retry) {
                ids.clear();
                long sleepTime = this.generateSleepTime();
                totalSleepTime += sleepTime;

                if (totalSleepTime >= this.maximumLockAttemptTime)
                    throw new RuntimeException(String.format("Could not lock. Too much time to try (%dms)", totalSleepTime));

                this.sleep(sleepTime);
            }

            for (String id : keys) {
                XLock xlock = this.getXLock(id);
                if (xlock.tryLock()) {
                    ids.add(xlock);
                } else {
                    for (XLock lockedId : ids) {
                        lockedId.unlock();
                    }
                    fail = true;
                    break;
                }
            }

            retry = fail;
        } while (retry);

        return new LocalLockedIds(ids);
    }

    @Override
    public boolean hasLockedTreads() {
        this.innerLock.lock();
        try {
            return !this.allLockedIds.isEmpty();
        } finally {
            this.innerLock.unlock();
        }
    }

    /*
     *
     *
     * */
    private long generateSleepTime() {
        if (this.maximumWaitTimeBeforeNewLockAttempt == this.minimumWaitTimeBeforeNewLockAttempt)
            return this.maximumWaitTimeBeforeNewLockAttempt;

        return (long) ((Math.random() * (this.maximumWaitTimeBeforeNewLockAttempt - this.minimumWaitTimeBeforeNewLockAttempt)) + this.minimumWaitTimeBeforeNewLockAttempt);
    }

    private void remove(XLock xLock) {
        this.innerLock.lock();
        this.allLockedIds.remove(xLock.getId());
        this.innerLock.unlock();
    }

    private void sleep(long sleepTime) {
        try {
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private XLock getXLock(String id) {
        this.innerLock.lock();
        try {
            return this.allLockedIds.compute(id, (key, value) -> {
                if (value == null) {
                    value = new XLock(key);
                }
                value.busy();
                return value;
            });
        } finally {
            this.innerLock.unlock();
        }
    }

    /*
     *
     *
     * */
    private class XLock {
        private final ReentrantLock rl = new ReentrantLock();
        private final String id;
        private final Set<Long> threads = Collections.newSetFromMap(new ConcurrentHashMap<>());

        public XLock(String id) {
            this.id = id;
        }

        public boolean tryLock() {
            return this.rl.tryLock();
        }

        public void busy() {
            this.threads.add(Thread.currentThread().getId());
        }

        public void unlock() {
            this.threads.remove(Thread.currentThread().getId());
            if (!this.rl.hasQueuedThreads() && this.threads.isEmpty()) OptimisticLocalLocker.this.remove(this);

            rl.unlock();
        }

        public String getId() {
            return this.id;
        }
    }

    public class LocalLockedIds implements LockedIds {
        private final List<XLock> locks;

        private LocalLockedIds(List<XLock> locks) {
            this.locks = locks;
        }

        @Override
        public void close() {
            this.release();
        }

        @Override
        public void release() {
            for (XLock xlock : this.locks) {
                xlock.unlock();
            }
        }

        @Override
        public String toString() {
            String result = "Locked ids: ";
            for (XLock xLock : this.locks) {
                result = result.concat(xLock.id).concat("; ");
            }
            return result;
        }
    }

}

