package gnoolson.locker;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class OptimisticLocalLocker implements Locker {

    private final AtomicInteger lockedThreads = new AtomicInteger();
    private final ReentrantLock innerLock = new ReentrantLock();
    private final Map<String, XLock> allLockedKeys = new HashMap<>();
    private final long minimumWaitTimeBeforeNewLockAttempt;
    private final long maximumWaitTimeBeforeNewLockAttempt;
    private final long maximumLockAttemptTime;
    private final String GLOBAL_LOCK_KEY = "GLOBAL_LOCK";

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

        boolean tryGlobalLock = false;
        if (keys.length == 0) {
            keys = new String[]{GLOBAL_LOCK_KEY};
            tryGlobalLock = true;
        }

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
                Optional<XLock> xlockOpt = this.getXLock(key, tryGlobalLock);
                if(xlockOpt.isPresent() && xlockOpt.get().tryLock()) {
                    lockedKeys.add(xlockOpt.get());
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
    public int getLockedThreadsSize() {
        return lockedThreads.get();
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

    private Optional<XLock> getXLock(String key, boolean tryGlobalLock) {
        this.innerLock.lock();
        try {
            if (!tryGlobalLock && allLockedKeys.containsKey(GLOBAL_LOCK_KEY)) {
                return Optional.empty();
            }

           if(tryGlobalLock && !allLockedKeys.isEmpty()){
               if(allLockedKeys.size() != 1 || !allLockedKeys.containsKey(GLOBAL_LOCK_KEY)){
                   return Optional.empty();
               }
           }

           XLock xlock = this.allLockedKeys.compute(key, (_key, value) -> {
                if (value == null) {
                    value = new XLock(_key);
                }
                value.busy();
                return value;
            });

            return Optional.of(xlock);
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
            if (result) {
                this.counter++;
                if (this.counter == 1) {
                    OptimisticLocalLocker.this.lockedThreads.incrementAndGet();
                }
            }
            return result;
        }

        public void busy() {
            this.threads.add(Thread.currentThread().getId());
        }

        public void unlock() {
            this.counter--;
            if (this.counter == 0) {
                this.threads.remove(Thread.currentThread().getId());
                if (!this.rl.hasQueuedThreads() && this.threads.isEmpty()) {
                    OptimisticLocalLocker.this.remove(this);
                }
                OptimisticLocalLocker.this.lockedThreads.decrementAndGet();
            }

            this.rl.unlock();
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

