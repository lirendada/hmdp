package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long bloggerId, Boolean isFollow) {
        // 获取当前用户ID
        UserDTO currentUser = UserHolder.getUser();

        Long userId = currentUser.getId();
        if(isFollow == true) {
            // 1. 如果isFollow为true，表示点击了关注
            // 1.1 向数据库中插入关注信息
            Follow follow = new Follow();
            follow.setUserId(bloggerId);
            follow.setFollowUserId(userId);
            boolean isSuccess = save(follow);

            // 1.2 插入当前用户关注的博主到redis中，方便后面查看共同关注
            if(isSuccess) {
                stringRedisTemplate.opsForSet().add(RedisConstants.FOLLOW_KEY + userId, bloggerId.toString());
            }

        } else {
            // 2. 如果isFollow为false，表示要取消关注
            // 2.1 将数据库中该关注信息删除
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", bloggerId)
                    .eq("follow_user_id", userId));

            // 2.2 删除redis中当前用户关注的博主id
            if(isSuccess) {
                stringRedisTemplate.opsForSet().remove(RedisConstants.FOLLOW_KEY + userId, bloggerId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long bloggerId) {
        // 1. 获取当前用户
        Long id = UserHolder.getUser().getId();

        // 2. 判断当前用户在redis的关注集合中是否有该博主
        Boolean hasMember = stringRedisTemplate.opsForSet().isMember(RedisConstants.FOLLOW_KEY + id, bloggerId.toString());

        // 3. 返回
        return Result.ok(BooleanUtil.isTrue(hasMember));
    }

    @Override
    public Result commonFollow(Long bloggerId) {
        // 1. 获取当前用户和博主关注的集合的交集
        Long id = UserHolder.getUser().getId();
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(RedisConstants.FOLLOW_KEY + id, RedisConstants.FOLLOW_KEY + bloggerId);
        if(CollectionUtil.isEmpty(intersect)) {
            return Result.ok(Collections.emptyList());
        }

        // 2. 转化为ids
        List<Long> ids = intersect.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());

        // 3. 查询数据库拿到用户信息
        List<UserDTO> userDTOS = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        // 4. 返回用户信息
        return Result.ok(userDTOS);
    }
}
