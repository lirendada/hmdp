package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private StringRedisTemplate stringRedisTemplate;

    private String transaction_name; // 业务名称，用于拼接key

    private String uuid = UUID.randomUUID().toString(); // uuid用于区分不同jvm中的同一线程

    private final static String KEY_PREFIX = "lock:"; // key前缀

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String transaction_name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.transaction_name = transaction_name;
    }

    private final static DefaultRedisScript<Long> unlockRedisScript;

    static {
        unlockRedisScript = new DefaultRedisScript<>();
        unlockRedisScript.setLocation(new ClassPathResource("unlock.lua"));
        unlockRedisScript.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(Long expireTime) {
        // 1. 获取 “uuid + 线程id” 标识作为value
        //  💥uuid用于区分不同jvm中的同一线程
        //  💥线程id用于区分同一jvm中的线程
        String threadId = uuid + "-" + Thread.currentThread().getId();

        // 2. 存放到redis中
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + transaction_name, threadId, expireTime, TimeUnit.SECONDS);

        // 3. 返回是否存放成功
        return BooleanUtil.isTrue(isLock);
    }

    @Override
    public void unlock() {
//        // 1. 获取当前线程的 “uuid + 线程id” 标识
//        String threadId = uuid + "-" + Thread.currentThread().getId();
//
//        // 2. 获取redis中该锁的标识
//        String s = stringRedisTemplate.opsForValue().get(KEY_PREFIX + transaction_name);
//
//        // 3. 判断当前标识是否与redis中的标识相同
//        if(threadId.equals(s)) {
//            // 4. 如果相同，则删除该锁
//            stringRedisTemplate.delete(KEY_PREFIX + transaction_name);
//        }

        stringRedisTemplate.execute(
                unlockRedisScript,
                Collections.singletonList(KEY_PREFIX + transaction_name),
                uuid + "-" + Thread.currentThread().getId());
    }
}