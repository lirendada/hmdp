package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getList() {
        // 从redis中查询商店类型
        String shop_type_key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        List<String> list = stringRedisTemplate.opsForList().range(
                shop_type_key,
                0,
                stringRedisTemplate.opsForList().size(shop_type_key) - 1
        );

        // 存在的话直接返回
        if(!CollectionUtils.isEmpty(list)) {
            // 需要转化为对象数组
            List<ShopType> collect = list.stream()
                    .map(str -> JSONUtil.toBean(str, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(collect);
        }

        // 不存在的话查询数据库，拿到商店类型
        List<ShopType> shopTypes = list();

        // 数据库中不存在则直接返回错误
        if(CollectionUtils.isEmpty(shopTypes)) {
            return Result.fail("商铺类型不存在！");
        }

        // 存在的话存储到redis中（设置过期时间)
        List<String> json_list = shopTypes.stream()
                .map(obj -> JSONUtil.toJsonStr(obj))
                .collect(Collectors.toList());

        stringRedisTemplate.opsForList().rightPushAll(shop_type_key, json_list);
        stringRedisTemplate.expire(shop_type_key, RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);

        // 返回
        return Result.ok(shopTypes);
    }
}
