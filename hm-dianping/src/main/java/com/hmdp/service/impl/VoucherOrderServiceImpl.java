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
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private RedisWorker redisWorker;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final static DefaultRedisScript<Long> SeckillRedisScript;

    static {
        SeckillRedisScript = new DefaultRedisScript<>();
        SeckillRedisScript.setLocation(new ClassPathResource("seckill.lua"));
        SeckillRedisScript.setResultType(Long.class);
    }

    private final BlockingQueue<VoucherOrder> queue = new ArrayBlockingQueue<>(1024 * 1024); // 阻塞队列
    private final static ExecutorService pool = Executors.newSingleThreadExecutor();

    private IVoucherOrderService proxy;

    @PostConstruct
    private void init() {
        pool.submit(new consumeOrder());
    }

    // 异步消费订单
    private class consumeOrder implements Runnable {
        @Override
        public void run() {
            while(true) {
                try {
                    // 1. 获取队列中的订单消息
                    VoucherOrder order = queue.take();

                    // 2. 处理订单业务（比如存储等）
                    handleOrder(order);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    // 真正处理订单的方法
    private void handleOrder(VoucherOrder order) {
        // 1. 首先获取分布式锁对象
        RLock lock = redissonClient.getLock("order:" + order.getUserId());

        // 2. 尝试上锁
        boolean isLock = lock.tryLock();

        // 3. 如果没拿到锁，说明同一用户已经拿到锁并且大概率要抢购订单了，所以当前线程不能再获取了
        if(!isLock) {
            log.error("您已经抢过优惠券了，请勿重复抢购！");
            return;
        }

        // 4. 如果拿到锁了，则开始抢购业务
        try {
            proxy.createOrder(order);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckill(Long voucherId) {
        // 1. 执行lua脚本
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SeckillRedisScript, Collections.emptyList(), voucherId, userId);

        // 2. 判断是否异常
        if(result != 0) {
            return Result.fail(result.intValue() == 1? "库存不足" : "用户已下单过了");
        }

        // 3. 保存到阻塞队列中
        long orderId = redisWorker.nextId("order");
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setVoucherId(voucherId);
        order.setUserId(userId);
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        queue.add(order);

        // 4. 返回订单id
        return Result.ok(orderId);
    }

    @Transactional
    public void createOrder(VoucherOrder order) {
        Long userId = order.getUserId();
        Long voucherId = order.getVoucherId();

        synchronized (userId.toString().intern()) {
            // 判断是否重复购买
            Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if(count > 0) {
                log.error("已经购买过优惠券了，不可重复购买！");
                return;
            }

            // 扣减库存
            boolean isDeduct = seckillVoucherService.update()
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0) // 利用CAS机制，防止并发问题💥
                    .setSql("stock = stock - 1")
                    .update();
            if(!isDeduct) {
                log.error("库存不足！");
            }

            // 7. 保存订单到数据库
            save(order);
        }
    }

//    @Override
//    public Result seckill(Long voucherId) {
//        // 1. 根据id，获取优惠券信息
//        SeckillVoucher seckillVoucher = seckillVoucherService.query().eq("voucher_id", voucherId).one();
//
//        // 2. 判断秒杀是否开始
//        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("活动尚未开始！");
//        }
//
//        // 3. 判断秒杀是否结束
//        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("活动已经结束！");
//        }
//
//        // 4. 判断是否有库存
//        if(seckillVoucher.getStock() < 1) {
//            return Result.fail("优惠券已被抢完！");
//        }
//
//        // 💥① 需要在方法外面加锁，因为该方法有@Transactional修饰，等到方法结束才会提交事务，所以如果在方法内加锁，等到解锁后事务才会提交，同样会有并发安全问题！
//        // 💥② 因为加锁是为了同一个用户只能下单一次，所以是互斥同一个用户，那么对于不同用户来说就没办法互斥了，所以可以用userId作为锁对象，提高效率！
//        // 💥③ 由于toString()每次都会创建一个新对象，所以锁就不一样，没办法互斥同一个用户，正确做法是使用 intern() 方法将字符串放到字符串常量池，这样子保证每个用户id每次拿到的字符串对象都是同一个
//        Long userId = UserHolder.getUser().getId();
////        synchronized(userId.toString().intern()) {
////            IVoucherOrderService orderService = (IVoucherOrderService) AopContext.currentProxy();
////            return orderService.deductStock(voucherId, userId);
////        }
//
//        // 引入分布式锁💥
//        // 1. 首先获取分布式锁对象
////        SimpleLock lock = new SimpleLock(stringRedisTemplate, "order:" + userId);
////
////        // 2. 判断是否获取锁
////        boolean isLock = lock.tryLock(120L);
////        if(!isLock) {
////            // 3. 如果没拿到锁，说明同一用户已经拿到锁并且大概率要抢购订单了，所以当前线程不能再获取了
////            return Result.fail("您已经抢过优惠券了，请勿重复抢购！");
////        }
////
////        // 4. 如果拿到锁了，则开始抢购业务
////        try {
////            IVoucherOrderService orderService = (IVoucherOrderService) AopContext.currentProxy();
////            return orderService.deductStock(voucherId, userId);
////        } finally {
////            lock.unlock(); // 别忘了释放锁！！！
////        }
//
//
//        // 引入redisson分布式锁💥💥
//        // 1. 首先获取分布式锁对象
//        RLock lock = redissonClient.getLock("order:" + userId);
//
//        // 2. 尝试上锁
//        boolean isLock = lock.tryLock();
//
//        // 3. 如果没拿到锁，说明同一用户已经拿到锁并且大概率要抢购订单了，所以当前线程不能再获取了
//        if(!isLock) {
//            return Result.fail("您已经抢过优惠券了，请勿重复抢购！");
//        }
//
//        // 4. 如果拿到锁了，则开始抢购业务
//        try {
//            IVoucherOrderService orderService = (IVoucherOrderService) AopContext.currentProxy();
//            return orderService.deductStock(voucherId, userId);
//        } finally {
//            lock.unlock();
//        }
//    }

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
