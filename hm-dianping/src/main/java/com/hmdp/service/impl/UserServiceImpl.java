package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;
import static java.time.LocalTime.now;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 检验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 如果手机号不合法，直接返回错误
            return Result.fail("手机号不合法，请重新输入！");
        }

        // 3. 生成验证码
        String code = RandomUtil.randomNumbers(6);

//        // 4. 保存验证码到session
//        session.setAttribute("code", code);

        // 4. 保存验证码到redis中（要有过期时间）
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code,
                RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5. 发送验证码（用日志模拟）
        log.info(code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String code = loginForm.getCode();
        String phone = loginForm.getPhone();

        // 1. 校验手机
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 如果手机号不合法，直接返回错误
            return Result.fail("手机号不合法，请重新输入！");
        }

        // 3. 校验验证码（改从redis中获取）
//        String session_code = (String)session.getAttribute("code");
        String session_code = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);

        if (RegexUtils.isCodeInvalid(session_code) || !code.equals(session_code)) {
            // 4. 如果不正确直接返回错误
            return Result.fail("验证码不正确！");
        }

        // 5. 判断用户是否存在
        User user = query().eq("phone", phone).one();

        // 6. 如果用户不存在，则创建新的用户信息，保存到数据库中
        if (user == null) {
            user = new User();
            user.setPhone(phone);
            user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            save(user);
        }

//        // 7. 将用户信息存放到session中
//        UserDTO userDTO = new UserDTO();
//        BeanUtils.copyProperties(user, userDTO);
//        session.setAttribute("user", userDTO);

        // 7. 将用户信息存放到redis中，并且使用 Hash 形式存储
        //  7.1 生成token
        String token = UUID.randomUUID(true).toString();

        //  7.2 将UserDto对象转为HashMap存储
        // 💥此时如果直接把非String类型数据存放到redis中，会报错，需要将数据类型都先映射为String类型
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        Map<String, Object> user_map = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> {
                            if (fieldValue == null) {
                                return null; // 避免 NPE
                            }
                            return fieldValue.toString();
                        }));

        //  7.3 存储到redis中
        String token_key = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(token_key, user_map);

        //  7.4 设置过期时间
        stringRedisTemplate.expire(token_key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //  7.5 登录成功则删除验证码信息
        stringRedisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + phone);

        // 8. 返回token给前端
        return Result.ok(token);
    }

    @Override
    public Result userSign() {
        // 1. 获取用户信息
        Long userId = UserHolder.getUser().getId();

        // 2. 获取日期
        LocalDateTime now = LocalDateTime.now();

        // 3. 拼接成key
        String key_suffix = now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + key_suffix;

        // 4. 获取现在是一个月的哪一天
        int dayOfMonth = now.getDayOfMonth();

        // 5. 写入redis
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);

        return Result.ok();
    }
}
