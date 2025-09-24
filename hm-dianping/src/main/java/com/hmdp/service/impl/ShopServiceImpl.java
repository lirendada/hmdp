package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
        // 1. 从redis中查询shop缓存（这里用string类型演示）
        String cache_key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(cache_key);

        // 2. 存在，并且有内容，说明是真的有信息，则直接返回
        if (StringUtils.hasText(shopJson)) {
            return Result.ok(JSONUtil.toBean(shopJson, Shop.class));
        }

        // 存在，但是没有内容，说明是空对象，是为了避免缓存穿透的
        if(shopJson != null) {
            return Result.fail("商铺不存在！");
        }

        // 3. 不存在，根据id查询数据库
        Shop shop = getById(id);

        // 4. 不存在，不是直接返回错误，而是将该id写入redis，设为空对象，设置ttl，避免缓存穿透💥
        if(shop == null) {
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.CACHE_SHOP_KEY + id,
                    "",
                    RedisConstants.CACHE_NULL_TTL,
                    TimeUnit.MINUTES);
            return Result.fail("商铺不存在！");
        }

        // 5. 存在，将数据写到redis中，设置过期时间（缓存更新策略）
        stringRedisTemplate.opsForValue().set(cache_key, JSONUtil.toJsonStr(shop));
        stringRedisTemplate.expire(cache_key, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 6. 返回
        return Result.ok(shop);
    }

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
