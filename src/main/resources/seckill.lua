-- 1. 参数列表
-- 1.1 优惠券的id
local voucherId = ARGV[1]

-- 1.2 用户的id
local userId = ARGV[2]
-- 2. 数据key
-- 2.1 优惠券的库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2 优惠券的订单key
local orderKey = 'seckill:order:' .. voucherId

-- 3. 脚本逻辑
-- 3.1 判断优惠券是否还有库存
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 没有库存，返回1
    return 1
end

-- 3.2 判断用户是否已经购买过
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 已经购买过，返回2
    return 2
end

-- 3.3 扣减库存
redis.call('incrby', stockKey, -1)

-- 3.4 将用户id添加到优惠券的订单集合中
redis.call('sadd', orderKey, userId)