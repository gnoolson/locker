package gnoolson.locker;

public interface Locker {

    LockedKeys lock(String... keys);

    boolean hasLockedTreads();

    interface LockedKeys extends AutoCloseable {

        void release();

        @Override
        void close();
    }

}
