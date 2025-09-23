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
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 因为所有请求都会先经过RefrashInterceptor，所以用户信息存在的就会在ThreadLocal中，可以据此判断是否登录
        UserDTO user = UserHolder.getUser();
        if(user == null) {
            // 如果不存在，直接拦截
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        // 放行
        return true;
    }
}
