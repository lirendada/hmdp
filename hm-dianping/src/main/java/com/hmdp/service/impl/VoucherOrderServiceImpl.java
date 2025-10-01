package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.SimpleLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private RedisWorker redisWorker;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckill(Long voucherId) {
        // 1. 根据id，获取优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.query().eq("voucher_id", voucherId).one();

        // 2. 判断秒杀是否开始
        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("活动尚未开始！");
        }

        // 3. 判断秒杀是否结束
        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("活动已经结束！");
        }

        // 4. 判断是否有库存
        if(seckillVoucher.getStock() < 1) {
            return Result.fail("优惠券已被抢完！");
        }

        // 💥① 需要在方法外面加锁，因为该方法有@Transactional修饰，等到方法结束才会提交事务，所以如果在方法内加锁，等到解锁后事务才会提交，同样会有并发安全问题！
        // 💥② 因为加锁是为了同一个用户只能下单一次，所以是互斥同一个用户，那么对于不同用户来说就没办法互斥了，所以可以用userId作为锁对象，提高效率！
        // 💥③ 由于toString()每次都会创建一个新对象，所以锁就不一样，没办法互斥同一个用户，正确做法是使用 intern() 方法将字符串放到字符串常量池，这样子保证每个用户id每次拿到的字符串对象都是同一个
        Long userId = UserHolder.getUser().getId();
//        synchronized(userId.toString().intern()) {
//            IVoucherOrderService orderService = (IVoucherOrderService) AopContext.currentProxy();
//            return orderService.deductStock(voucherId, userId);
//        }

        // 引入分布式锁💥
        // 1. 首先获取分布式锁对象
        SimpleLock lock = new SimpleLock(stringRedisTemplate, "order:" + userId);

        // 2. 判断是否获取锁
        boolean isLock = lock.tryLock(120L);
        if(!isLock) {
            // 3. 如果没拿到锁，说明同一用户已经拿到锁并且大概率要抢购订单了，所以当前线程不能再获取了
            return Result.fail("您已经抢过优惠券了，请勿重复抢购！");
        }

        // 4. 如果拿到锁了，则开始抢购业务
        try {
            IVoucherOrderService orderService = (IVoucherOrderService) AopContext.currentProxy();
            return orderService.deductStock(voucherId, userId);
        } finally {
            lock.unlock(); // 别忘了释放锁！！！
        }
    }

    @Transactional
    public Result deductStock(Long voucherId, Long userId) {
        // 5. 解决一人一单问题💥
        //  5.1 根据用户id，查询对应订单
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //  5.2 如果存在对应订单，则直接返回
        if(count > 0) {
            return Result.fail("已经购买过优惠券了，不可重复购买！");
        }

        // 6. 如果有库存，则库存减少一个
        boolean isDeduct = seckillVoucherService.update()
                .eq("voucher_id", voucherId)
                .gt("stock", 0) // 利用CAS机制，防止并发问题💥
                .setSql("stock = stock - 1")
                .update();
        if(!isDeduct) {
            return Result.fail("库存不足！");
        }

        // 7. 创建订单，并且保存到数据库
        VoucherOrder order = new VoucherOrder();
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setId(redisWorker.nextId("order")); // 使用redis创建的全局唯一ID，作为订单ID
        save(order);

        // 8. 返回订单ID
        return Result.ok(order.getId());
    }
}
