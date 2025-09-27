package com.hmdp.utils;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RedisWorkerTest {
    @Autowired
    private RedisWorker redisWorker;

    private ExecutorService pool = Executors.newFixedThreadPool(500);

    @Test
    void nextId() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);

        Runnable task = () -> {
            for(int i = 0; i < 100; ++i) {
                long id = redisWorker.nextId("order");
                System.out.println("id = " + id);
            }
            countDownLatch.countDown();
        };

        long begin = System.currentTimeMillis();
        for(int i = 0; i < 300; ++i) {
            pool.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("用时：" + (end - begin) + "ms");
    }
}