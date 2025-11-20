package gnoolson.locker;


import org.junit.jupiter.api.RepeatedTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LockerTest {

    private final int threads = 64;

    @RepeatedTest(value = 64, name = "{currentRepetition}/{totalRepetitions}")
    public void many_lockers() throws InterruptedException {
        Locker locker = new OptimisticLocalLocker();

        Counter c1 = new Counter(String.valueOf(1));
        Counter c2 = new Counter(String.valueOf(2));
        Counter c3 = new Counter(String.valueOf(3));
        Counter c4 = new Counter(String.valueOf(4));
        Counter c5 = new Counter(String.valueOf(5));
        Counter c6 = new Counter(String.valueOf(6));

        ExecutorService ex = Executors.newCachedThreadPool();

        CountDownLatch cdl = new CountDownLatch(threads * 6);

        beginChanges(ex, locker, cdl, c1, c2, c3, c4, c5);
        beginChanges(ex, locker, cdl, c2, c3, c4, c5, c6);
        beginChanges(ex, locker, cdl, c3, c4, c5, c6, c1);
        beginChanges(ex, locker, cdl, c4, c5, c6, c1, c2);
        beginChanges(ex, locker, cdl, c5, c6, c1, c2, c3);
        beginChanges(ex, locker, cdl, c6, c1, c2, c3, c4);

        cdl.await();
        ex.shutdownNow();

        assertEquals(threads * 5, c1.value);
        assertEquals(threads * 5, c2.value);
        assertEquals(threads * 5, c3.value);
        assertEquals(threads * 5, c4.value);
        assertEquals(threads * 5, c5.value);
        assertEquals(threads * 5, c6.value);
        assertFalse(locker.hasLockedTreads());
    }

    @RepeatedTest(value = 64, name = "{currentRepetition}/{totalRepetitions}")
    public void single_locker() throws InterruptedException {
        Locker locker = new OptimisticLocalLocker();

        Counter c1 = new Counter(String.valueOf(1));
        ExecutorService ex = Executors.newCachedThreadPool();
        CountDownLatch cdl = new CountDownLatch(threads * 6);

        beginChanges(ex, locker, cdl, c1);
        beginChanges(ex, locker, cdl, c1);
        beginChanges(ex, locker, cdl, c1);
        beginChanges(ex, locker, cdl, c1);
        beginChanges(ex, locker, cdl, c1);
        beginChanges(ex, locker, cdl, c1);

        cdl.await();
        ex.shutdownNow();

        assertEquals(384, c1.value);
        assertFalse(locker.hasLockedTreads());
    }

    /*
     *
     *
     * */
    void beginChanges(ExecutorService ex, Locker locker, CountDownLatch cdl, Counter... counters) {

        String[] ids = Stream.of(counters).map((counter) -> {
            return counter.id;
        }).toArray(value -> new String[counters.length]);

        ex.submit(() -> {
            for (int i = 0; i < threads; i++) {
                ex.submit(() -> {
                    try {
                        Thread.sleep(1L);
                        try (Locker.LockedIds key = locker.lock(ids)) {
                            for (Counter counter : counters) {
                                counter.inc();
                            }
                        } finally {
                            cdl.countDown();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        });

    }

    class Counter {
        private final String id;
        private int value = 0;

        public Counter(String id) {
            this.id = id;
        }

        public void inc() {
            this.value = this.value + 1;
        }
    }

}