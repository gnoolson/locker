package gnoolson.locker;

import org.junit.jupiter.api.Test;

public class Dev {


    @Test
    public void test(){
        Locker locker = new OptimisticLocalLocker();

        try(Locker.LockedKeys lockedKeys = locker.lockKeys("teeeest")) {
            System.out.println(locker.isKeysLocked("teeeest"));
            throw new RuntimeException();
        } finally {
            System.out.println(locker.isKeysLocked("teeeest"));
        }

    }

}
