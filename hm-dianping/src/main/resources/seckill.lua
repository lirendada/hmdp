-- 参数：订单ID、用户ID
local voucherId = ARGV[1]
local userId = ARGV[2]

-- 键
local stock_key = "seckill:stock:" .. voucherId
local order_key = "seckill:order:" .. voucherId

-- 获取库存
local stock = redis.call("get", stock_key)
if (stock == false) then
    return 3   -- 表示库存数据不存在
end

-- 判断库存是否充足
if (tonumber(stock) < 1) then
    return 1
end

-- 判断用户是否下单
if(redis.call("sismember", order_key, userId) == 1) then
    return 2
end

-- 扣减库存
redis.call("incrby", stock_key, -1)

-- 将userId存入当前优惠券的set集合
redis.call("sadd", order_key, userId)

return 0