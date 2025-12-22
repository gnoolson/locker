package gnoolson.locker;

public interface Locker {

    LockedKeys lock(String... keys);

    int getLockedThreadsSize();

    interface LockedKeys extends AutoCloseable {

        void release();

        @Override
        void close();
    }

}
