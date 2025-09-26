package com.hmdp.service.impl;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getShopByID(Long id) {
//        Shop shop = getShopByPenetrate(id);
//        Shop shop = getShopByBreakDownMutex(id);
        Shop shop = getShopByBreakDownLogicExpire(id);

        if(shop == null) {
            return Result.fail("该商铺不存在！");
        }

        // 返回
        return Result.ok(shop);
    }


    /*
    // 缓存击穿（互斥锁实现方式，因为没有逻辑过期，所以会过期，请求可能直接打在数据库上，所以需要防止缓存穿透）
    private Shop getShopByBreakDownMutex(Long id) {
        // 1. 从redis中查询shop缓存（这里用string类型演示）
        String cache_key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(cache_key);

        // 2. 存在缓存，并且有内容，说明是真的有信息，则直接返回
        if (StringUtils.hasText(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 存在缓存，但是没有内容，说明是空对象，是为了避免缓存穿透的，直接返回null
        if(shopJson != null) {
            return null;
        }

        try {
            // 3. 不存在缓存，先获取锁
            boolean isLock = tryLock(id);

            // 3.1 如果拿不到锁，休眠后重试
            if (!isLock) {
                Thread.sleep(50);
                return getShopByBreakDownMutex(id); // 递归去调用重试
            }

            // 3.2 如果拿到锁，进行doublecheck，防止不必要的访问数据库
            String doublecheck = stringRedisTemplate.opsForValue().get(cache_key);
            if(StringUtils.hasText(doublecheck)) {
                return JSONUtil.toBean(doublecheck, Shop.class);
            }

            // 实在没有再去查数据库
            Shop shop = getById(id);

            // 3.3 如果数据库中不存在，不是直接返回错误，而是将该id写入redis，设为空对象，设置ttl，避免缓存穿透💥
            if(shop == null) {
                stringRedisTemplate.opsForValue().set(
                        RedisConstants.CACHE_SHOP_KEY + id,
                        "",
                        RedisConstants.CACHE_NULL_TTL,
                        TimeUnit.MINUTES);
                return null;
            }

            // 3.4 数据库中存在，将数据写到redis中，设置过期时间（缓存更新策略）
            stringRedisTemplate.opsForValue().set(cache_key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

            return shop;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(id);
        }
    }
    */

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        // 更新数据库
        Long id = shop.getId();
        if(id == null) {
            return Result.fail("商铺id不能为空！");
        }
        updateById(shop);

        // 删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok("更新商铺成功！");
    }
}
