package gnoolson.locker;

public interface Locker {

    LockedKeys lock(String... keys);

    LockedKeys globalLock();

    boolean hasLockedThreads();

    boolean isKeysLocked(String... keys);

    interface LockedKeys extends AutoCloseable {

        void release();

        @Override
        void close();
    }

}
