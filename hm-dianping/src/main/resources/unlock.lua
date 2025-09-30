-- 1. 获取锁标识
-- 2. 判断锁标识是否一致
-- 3. 一致的话对锁删除

if(redis.call('get', KEYS[1]) == ARGV[1]) then
    return redis.call('del', KEYS[1])
end
return 0