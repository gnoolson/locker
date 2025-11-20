package gnoolson.locker;

public interface Locker {

    LockedIds lock(String... ids);

    boolean hasLockedTreads();

    interface LockedIds extends AutoCloseable {

        void release();

        @Override
        void close();
    }

}
