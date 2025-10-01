package com.hmdp;

import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@SpringBootTest
public class TestRedisson {
    @Resource
    private RedissonClient redissonClient;

    @Test
    public void testRedisson() throws InterruptedException {
        // 获取可重入锁
        RLock anylock = redissonClient.getLock("anylock");

        // 尝试上锁，三个参数分别是：获取锁的最大等待时间(期间会重试)，锁的自动释放时间，时间单位
        boolean isLock = anylock.tryLock(1, 10, TimeUnit.SECONDS);

        // 判断获取锁是否成功
        if(isLock) {
            try {
                System.out.println("执行业务。。。");
            } finally {
                anylock.unlock();
            }
        }
    }
}
