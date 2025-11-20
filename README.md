# Locker

The **Locker** library provides a simple interface for managing access to shared logical resources in a multithreaded environment.
The `OptimisticLocalLocker` implementation ensures that only one thread can access resources associated with their identifiers at any given time. If an attempt is made to lock resources that are already locked by another thread, the locker waits for the resources to be released and then retries the lock operation. The waiting parameters can be configured through the constructor.
## Simple Example

```java
package example;

import gnoolson.locker.Locker;
import gnoolson.locker.OptimisticLocalLocker;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Example {

    public static void main(String... args) throws Exception {

        class Counter {
            int value;
            void increment() {
                value++;
            }
        }

        Counter counter_1 = new Counter();
        Counter counter_2 = new Counter();
        Counter counter_3 = new Counter();
        Counter counter_4 = new Counter();
        Counter counter_5 = new Counter();
        String[] ids_1 = {"counter_1", "counter_2", "counter_3"};
        String[] ids_2 = {"counter_2", "counter_3", "counter_4"};
        String[] ids_3 = {"counter_3", "counter_4", "counter_5"};

        int threads = 48;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        Locker locker = new OptimisticLocalLocker();

        for (int i = 0; i < 16; i++) {
            ex.submit(() -> {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                try (Locker.LockedIds lockedIds = locker.lock(ids_1)) {
                    counter_1.increment();
                    counter_2.increment();
                    counter_3.increment();
                }
                latch.countDown();
            });
        }

        for (int i = 0; i < 16; i++) {
            ex.submit(() -> {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                try (Locker.LockedIds lockedIds = locker.lock(ids_2)) {
                    counter_2.increment();
                    counter_3.increment();
                    counter_4.increment();
                }
                latch.countDown();
            });
        }
        
        for (int i = 0; i < 16; i++) {
            ex.submit(() -> {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                try (Locker.LockedIds lockedIds = locker.lock(ids_3)) {
                    counter_3.increment();
                    counter_4.increment();
                    counter_5.increment();
                }
                latch.countDown();
            });
        }

        latch.await();
        ex.shutdownNow();

        System.out.println(counter_1.value == 16); // true
        System.out.println(counter_2.value == 32); // true
        System.out.println(counter_3.value == 48); // true
        System.out.println(counter_4.value == 32); // true
        System.out.println(counter_5.value == 16); // true
    }

}


