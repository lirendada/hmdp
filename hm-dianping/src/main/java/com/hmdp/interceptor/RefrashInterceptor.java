package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// 对一切请求进行拦截，刷新缓存
@Component
public class RefrashInterceptor implements HandlerInterceptor {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 拿到token，进行判断
        String token = request.getHeader("authorization");
        if (!StringUtils.hasText(token)) {
            // 如果不存在，直接放行，交给LoginInterceptor
            return true;
        }

        // 根据token到redis中获取用户信息
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);
        UserDTO user = BeanUtil.fillBeanWithMap(entries, new UserDTO(), false);

        // 判断用户是否存在
        if(user == null) {
            // 如果不存在，直接放行，交给LoginInterceptor
            return true;
        }

        // 存在的话，存放到threadlocal中，方便LoginInterceptor拿到用户信息进行判断
        UserHolder.saveUser(user);

        // 刷新redis中的用户信息过期时间
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 清理 ThreadLocal，防止内存泄漏
        UserHolder.removeUser();
    }
}
