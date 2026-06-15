package gnoolson.locker;

public interface Locker {

    LockHandle lockIds(String... ids);

    LockHandle lockGlobal();

    boolean hasLockedThreads();

    interface LockHandle extends AutoCloseable {
        @Override
        void close();
    }

}
