package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

// 缓存工具类
@Slf4j
@Component
public class CacheClient {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService executorService = Executors.newFixedThreadPool(10);

    // 方法1：将任意Java对象序列化为JSON，并存储到String类型的Key中，并可以设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    // 方法2：将任意Java对象序列化为JSON，并存储在String类型的Key中，并可以设置逻辑过期时间，用于处理缓存击穿问题
    public void setWithLogicExpireTime(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 方法3：根据指定的Key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    public <T, ID> T getShopPreventPenetrate(
            String prefix_key,
            ID id,
            Class<T> type,
            Function<ID, T> dbCallBack,
            Long time, TimeUnit unit) {
        // 1. 从redis中查询缓存
        String key = prefix_key + id;
        String jsonStr = stringRedisTemplate.opsForValue().get(key);

        // 2. 存在缓存，并且有内容，说明是真的有信息，则直接返回对象
        if (StringUtils.hasText(jsonStr)) {
            try {
                // 先尝试直接转为对象
                T bean;
                if (jsonStr.trim().startsWith("{")) {
                    JSONObject jsonObject = JSONUtil.parseObj(jsonStr);
                    // 如果存在 data 字段，就取 data 部分
                    if (jsonObject.containsKey("data")) {
                        bean = JSONUtil.toBean(jsonObject.getJSONObject("data"), type);
                    } else {
                        bean = JSONUtil.toBean(jsonObject, type);
                    }
                } else {
                    // 如果不是对象，而是字符串等，直接转
                    bean = JSONUtil.toBean(jsonStr, type);
                }
                return bean;
            } catch (Exception e) {
                log.error("反序列化缓存失败，jsonStr={}", jsonStr, e);
            }
        }

        // 存在缓存，但是没有内容，说明是空对象，是为了避免缓存穿透的，直接返回null
        if(jsonStr != null) {
            return null;
        }

        // 3. 不存在，根据id查询数据库
        T object = dbCallBack.apply(id);

        // 4. 不存在，不是直接返回错误，而是将该id写入redis，设为空对象，设置ttl，避免缓存穿透💥
        if(object == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 5. 存在，将数据写到redis中，设置过期时间（缓存更新策略）
        this.set(key, JSONUtil.toJsonStr(object), time, unit);

        return object;
    }

    // 方法4：根据指定的Key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
    //  缓存击穿（逻辑过期方式，因为缓存一直存在，所以请求不会直接打到数据库，并且数据库也有数据，所以不需要考虑缓存穿透）
    public <T, ID> T getShopPreventBreakDownByLogicExpire(
            String prefix_key,
            ID id,
            Class<T> type,
            Function<ID, T> dbCallBack,
            Long time, TimeUnit unit) {
        // 1. 从redis中查询缓存
        String key = prefix_key + id;
        String jsonStr = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断缓存是否命中
        if (!StringUtils.hasText(jsonStr)) {
            // 3. 如果没命中，说明数据库中也没有数据，直接返回null
            return null;
        }

        // 4. 如果命中了，拿到数据，判断缓存逻辑过期时间
        //   （因为Object通过redis序列化之后转化为字符串，但是反序列化之后不知道类型是什么，所以用通用类型JSONObject表示）
        RedisData redisData = JSONUtil.toBean(jsonStr, RedisData.class);
        JSONObject data = (JSONObject)redisData.getData();
        T object = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 5. 如果没过期，直接返回数据
        if(LocalDateTime.now().isBefore(expireTime)) {
            return object;
        }
        log.info("过期了！");

        // 6. 如果过期了，获取锁资源，判断锁资源是否拿到
        String lock_key = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lock_key);
        if (isLock) {
            try {
                // 7. 如果拿到锁资源，则创建新线程去重建缓存（这里用线程池）
                executorService.submit(() -> {
                    try {
                        T tmp = dbCallBack.apply(id); // 查数据库拿到数据
                        this.setWithLogicExpireTime(key, tmp, time, unit);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            } finally {
                unlock(lock_key); // 解锁必须在主线程执行
            }
        }

        // 8. 返回数据
        return object;
    }

    private boolean tryLock(String key) {
        // 为了防止线程崩了导致死锁，需要设置过期时间
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
