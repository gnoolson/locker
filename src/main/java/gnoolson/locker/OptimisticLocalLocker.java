package gnoolson.locker;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class OptimisticLocalLocker implements Locker {

    private final ReentrantLock innerLock = new ReentrantLock();
    private final Map<String, XLock> allLockedKeys = new HashMap<>();
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
    public LockedKeys lock(String... keys) {
        boolean retry = false;
        List<XLock> lockedKeys = new ArrayList<>(keys.length);
        long totalSleepTime = 0;

        do {
            boolean fail = false;
            if (retry) {
                lockedKeys.clear();

                if (totalSleepTime >= this.maximumLockAttemptTime)
                    throw new RuntimeException(String.format("Could not lock. Too much time to try (%dms)", totalSleepTime));

                long sleepTime = this.generateSleepTime();
                totalSleepTime += sleepTime;
                this.sleep(sleepTime);
            }

            for (String key : keys) {
                XLock xlock = this.getXLock(key);
                if (xlock.tryLock()) {
                    lockedKeys.add(xlock);
                } else {
                    for (XLock lockedKey : lockedKeys) {
                        lockedKey.unlock();
                    }
                    fail = true;
                    break;
                }
            }

            retry = fail;
        } while (retry);

        return new XLockedKeys(lockedKeys);
    }

    @Override
    public boolean hasLockedTreads() {
        this.innerLock.lock();
        try {
            return !this.allLockedKeys.isEmpty();
        } finally {
            this.innerLock.unlock();
        }
    }

    /*
     *
     *
     * */
    private long generateSleepTime() {
        return (long) ((Math.random() * (this.maximumWaitTimeBeforeNewLockAttempt - this.minimumWaitTimeBeforeNewLockAttempt)) + this.minimumWaitTimeBeforeNewLockAttempt);
    }

    private void remove(XLock xLock) {
        this.innerLock.lock();
        this.allLockedKeys.remove(xLock.getKey());
        this.innerLock.unlock();
    }

    private void sleep(long sleepTime) {
        try {
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private XLock getXLock(String key) {
        this.innerLock.lock();
        try {
            return this.allLockedKeys.compute(key, (_key, value) -> {
                if (value == null) {
                    value = new XLock(_key);
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
        private final String key;
        private final Set<Long> threads = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private int counter;

        public XLock(String key) {
            this.key = key;
        }

        public boolean tryLock() {
            boolean result = this.rl.tryLock();
            if(result)
                counter++;
            return result;
        }

        public void busy() {
            this.threads.add(Thread.currentThread().getId());
        }

        public void unlock() {
            counter--;
            if(counter == 0) {
                this.threads.remove(Thread.currentThread().getId());
                if (!this.rl.hasQueuedThreads() && this.threads.isEmpty()) {
                    OptimisticLocalLocker.this.remove(this);
                }
            }
            rl.unlock();
        }

        public String getKey() {
            return this.key;
        }
    }

    public class XLockedKeys implements LockedKeys {
        private final List<XLock> locks;

        private XLockedKeys(List<XLock> locks) {
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
            String result = "Locked keys: ";
            for (XLock xLock : this.locks) {
                result = result.concat(xLock.key).concat("; ");
            }
            return result;
        }
    }

}

