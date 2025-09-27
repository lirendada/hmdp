package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisWorker {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final static long beginTime = 1735689600L; // 设置起始时间（2025年1月1号0点0分0时）

    private final static long OFFSET_BIT = 32L;

    /**
     * 创建一个全局唯一的ID，由 “符号位 + 时间戳 + 序列号” 组成
     */
    public long nextId(String prefix_key) {
        // 1. 创建时间戳
        LocalDateTime now = LocalDateTime.now();
        long timeStamp = now.toEpochSecond(ZoneOffset.UTC) - beginTime;

        // 2. 通常increment指令，生成序列号（对于key，为了提高安全性，最好加上一个格式时间，让每天生成的序列号区分开）
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long serial = stringRedisTemplate.opsForValue().increment("inc:" + prefix_key + ":" + date);

        // 3. 拼接成id进行返回
        return timeStamp << OFFSET_BIT | serial;
    }

    public static void main(String[] args) {
        LocalDateTime tmp = LocalDateTime.of(2025, 1, 1, 0, 0);
        System.out.println(tmp.toString());
        System.out.println(tmp.getDayOfMonth());
        System.out.println(tmp.getMonth());
        System.out.println(tmp.toEpochSecond(ZoneOffset.UTC));
        System.out.println(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd")));
    }
}
