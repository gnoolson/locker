package gnoolson.locker;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class OptimisticLocalLocker implements Locker {

    private final String GLOBAL_LOCK_ID = new Object().toString();
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
        this(0, 5, 2000);
    }

    public OptimisticLocalLocker(long minimumWaitTimeBeforeNewLockAttempt, long maximumWaitTimeBeforeNewLockAttempt, long maximumLockAttemptTime) {
        if (minimumWaitTimeBeforeNewLockAttempt < 0)
            throw new RuntimeException("The minimum time is less than 0 ms");

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
    public LockHandle lockGlobal() {
        List<XLock> lockedIds = lockIds(true, GLOBAL_LOCK_ID);
        return new XLockHandle(lockedIds);
    }

    @Override
    public LockHandle lockIds(String... ids) {
        return new XLockHandle(lockIds(false, ids));
    }

    @Override
    public boolean hasLockedThreads() {
        this.innerLock.lock();
        try {
            return !allLockedIds.isEmpty();
        } finally {
            this.innerLock.unlock();
        }
    }

    /*
     *
     *
     * */
    private List<XLock> lockIds(boolean globalLock, String... ids) {
        boolean retry = false;
        List<XLock> lockedIds = new ArrayList<>(ids.length);
        long totalSleepTime = 0;

        do {
            boolean fail = false;
            if (retry) {
                lockedIds.clear();

                checkAttemptTime(totalSleepTime);

                long sleepTime = this.generateSleepTime();
                totalSleepTime += sleepTime;
                this.sleep(sleepTime);
            }

            for (String id : ids) {
                this.innerLock.lock();
                try {
                    if (!globalLock && isGlobalLockAlreadyActive()) {
                        if (!isGlobalLockOwnedByCurrentThread()) {
                            fail = true;
                            break;
                        }
                    }

                    if (globalLock && hasNormalLocks()) {
                        if (!areAllLocksOwnedByCurrentThread()) {
                            fail = true;
                            break;
                        }
                    }

                    XLock xlock = this.getXLock(id);
                    if (xlock.tryLock()) {
                        lockedIds.add(xlock);
                    } else {
                        unlockLockedIds(lockedIds);
                        fail = true;
                        break;
                    }
                } finally {
                    this.innerLock.unlock();
                }
            }

            retry = fail;
        } while (retry);

        return lockedIds;
    }

    private boolean isGlobalLockOwnedByCurrentThread() {
        XLock lock = allLockedIds.get(GLOBAL_LOCK_ID);
        if (lock == null)
            return false;

        return lock.isOwnedBy(Thread.currentThread().getId());
    }

    private void checkAttemptTime(long totalSleepTime) {
        if (totalSleepTime >= this.maximumLockAttemptTime)
            throw new RuntimeException(String.format("Could not lock. Too much time to try (%dms)", totalSleepTime));
    }

    private void unlockLockedIds(List<XLock> lockedIds) {
        for (XLock lockedId : lockedIds) {
            lockedId.unlock();
        }
    }

    private boolean areAllLocksOwnedByCurrentThread() {
        long threadId = Thread.currentThread().getId();

        for (XLock lock : allLockedIds.values()) {
            if (!lock.isOwnedBy(threadId)) {
                return false;
            }
        }
        return true;
    }

    private boolean isGlobalLockAlreadyActive() {
        return this.allLockedIds.containsKey(GLOBAL_LOCK_ID);
    }

    private boolean hasNormalLocks() {
        if (!this.allLockedIds.isEmpty()) {
            return this.allLockedIds.size() != 1 || !this.allLockedIds.containsKey(GLOBAL_LOCK_ID);
        }
        return false;
    }

    private long generateSleepTime() {
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
        return this.allLockedIds.compute(id, (_id, value) -> {
            if (value == null) {
                value = new XLock(_id);
            }
            value.busy();
            return value;
        });
    }

    /*
     *
     *
     * */
    private class XLock {
        private final ReentrantLock rl = new ReentrantLock();
        private final String id;
        private final Set<Long> threads = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private int counter;

        private XLock(String id) {
            this.id = id;
        }

        private boolean isOwnedBy(long threadId) {
            return threads.contains(threadId);
        }

        private boolean tryLock() {
            boolean result = this.rl.tryLock();
            if (result) {
                this.counter++;
            }
            return result;
        }

        private void busy() {
            this.threads.add(Thread.currentThread().getId());
        }

        private void unlock() {
            this.counter--;
            if (this.counter == 0) {
                this.threads.remove(Thread.currentThread().getId());
                if (!this.rl.hasQueuedThreads() && this.threads.isEmpty()) {
                    OptimisticLocalLocker.this.remove(this);
                }
            }
            this.rl.unlock();
        }

        private String getId() {
            return this.id;
        }
    }

    public class XLockHandle implements LockHandle {
        private final List<XLock> locks;
        private boolean unlocked;

        private XLockHandle(List<XLock> locks) {
            this.locks = locks;
        }

        @Override
        public void close() {
            if (this.unlocked)
                return;

            for (XLock xlock : this.locks) {
                xlock.unlock();
            }
            this.unlocked = true;
        }

        @Override
        public String toString() {
            String result = "XLockedIds: ";
            for (XLock xLock : this.locks) {
                result = result.concat(xLock.id).concat("; ");
            }
            return result;
        }
    }

}

