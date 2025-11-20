package gnoolson.locker;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class OptimisticLocalLocker implements Locker {

    private final ReentrantLock innerLock = new ReentrantLock();
    private final Map<String, XLock> allLockedIds = new HashMap<>();
    private final int minimumWaitingTimeForTry;
    private final int maximumWaitingTimeForTry;
    private final long maximumWaitingTimeForLock;

    /*
     *
     *
     * */
    public OptimisticLocalLocker() {
        this.minimumWaitingTimeForTry = 1;  // 1ms
        this.maximumWaitingTimeForTry = 10; // 10ms
        this.maximumWaitingTimeForLock = 2000; // 2000ms
    }

    public OptimisticLocalLocker(int minimumWaitingTimeForTry, int maximumWaitingTimeForTry, long maximumWaitingTimeForLock) {
        this.minimumWaitingTimeForTry = minimumWaitingTimeForTry;
        this.maximumWaitingTimeForTry = maximumWaitingTimeForTry;
        this.maximumWaitingTimeForLock = maximumWaitingTimeForLock;
    }

    public OptimisticLocalLocker(int waitingTimeForTry, long maximumWaitingTimeForLock) {
        this.minimumWaitingTimeForTry = waitingTimeForTry;
        this.maximumWaitingTimeForTry = waitingTimeForTry;
        this.maximumWaitingTimeForLock = maximumWaitingTimeForLock;
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

                if(totalSleepTime >= this.maximumWaitingTimeForLock)
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
        if(this.maximumWaitingTimeForTry == this.minimumWaitingTimeForTry)
            return this.maximumWaitingTimeForTry;

        return (long) ((Math.random() * (this.maximumWaitingTimeForTry - this.minimumWaitingTimeForTry)) + this.minimumWaitingTimeForTry);
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
            if (!this.rl.hasQueuedThreads() && this.threads.isEmpty())
                OptimisticLocalLocker.this.remove(this);

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

