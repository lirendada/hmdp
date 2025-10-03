package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.stream.CollectorUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            setUser(blog);
            // 追加判断blog是否被当前用户点赞，逻辑封装到isBlogLiked方法中
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlog(Long blogId) {
        // 获取博客
        Blog blog = getById(blogId);
        if(blog == null) {
            return Result.fail("博客不存在或已被删除！");
        }

        // 获取用户信息，填充Blog
        setUser(blog);

        // 追加判断blog是否被当前用户点赞，逻辑封装到isBlogLiked方法中
        isBlogLiked(blog);

        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        // 1. 获取用户信息
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;

        // 2. 判断博客是否已经点赞（通过zset命令判断是否 SECKILL_STOCK_KEY 中是否存在该用户）
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null) {
            // 3. 如果未点赞
            // 3.1 更新数据库中点赞数量+1
            boolean success = update().eq("id", id).setSql("liked = liked + 1").update();

            // 3.2 向redis中 SECKILL_STOCK_KEY 添加该用户
            if(success) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4. 如果已经点赞了
            // 4.1 更新数据库中点赞数量-1
            boolean success = update().eq("id", id).setSql("liked = liked - 1").update();

            // 4.2 将redis中 SECKILL_STOCK_KEY 删除该用户
            if(success) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result likesBlog(Long id) {
        // 拿到前五个用户id（存储的是string类型）
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> showList_string = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(CollectionUtil.isEmpty(showList_string)) {
            return Result.ok(Collections.emptyList()); // 不存在用户点赞，则直接返回空列表
        }

        // 转化为Long类型
        List<Long> showList_id = showList_string.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());

        // 查出User，然后转化为UserDTO类型，进行返回
        String idsStr = StrUtil.join(",", showList_id);
        List<UserDTO> userDTOS = userService.query().in("id", showList_id)
                .last("order by field(id, " + idsStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }


    private void isBlogLiked(Blog blog) {
        // 1. 获取用户信息
        UserDTO user = UserHolder.getUser();
        if(user == null || user.getId() == null) {
            return;
        }

        // 2. 判断当前用户是否已经点赞
        Double score = stringRedisTemplate.opsForZSet().score(
                RedisConstants.BLOG_LIKED_KEY + blog.getId(),
                user.getId().toString());

        // 3. 设置blog是否已经点赞
        if(score == null) {
            blog.setIsLike(false);
        } else {
            blog.setIsLike(true);
        }
    }


    private void setUser(Blog blog) {
        User user = userService.getById(blog.getUserId());
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
