package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
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

    @Override
    @Transactional
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

        // 5. 如果有库存，则库存减少一个
        boolean isDeduct = seckillVoucherService.update()
                .eq("voucher_id", voucherId)
                .gt("stock", 0) // 利用CAS机制，防止并发问题💥
                .setSql("stock = stock - 1")
                .update();
        if(!isDeduct) {
            return Result.fail("库存不足！");
        }

        // 6. 创建订单，并且保存到数据库
        VoucherOrder order = new VoucherOrder();
        order.setUserId(UserHolder.getUser().getId());
        order.setVoucherId(voucherId);
        order.setId(redisWorker.nextId("order")); // 使用redis创建的全局唯一ID，作为订单ID
        save(order);

        // 7. 返回订单ID
        return Result.ok(order.getId());
    }
}
