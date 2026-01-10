package gnoolson.locker;

public interface Locker {

    LockedKeys lockKeys(String... keys);

    LockedKeys lock();

    boolean hasLockedThreads();

    interface LockedKeys extends AutoCloseable {

        void release();

        @Override
        void close();
    }

}
