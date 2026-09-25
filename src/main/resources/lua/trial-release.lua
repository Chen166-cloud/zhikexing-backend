-- Only a DB-terminal rejected request may call this. Never invent inventory after key loss.
local receipt = redis.call('HGET', KEYS[3], ARGV[1])
if receipt == 'RELEASED' then return 0 end
if receipt ~= 'RESERVED' then return -1 end
if redis.call('HGET', KEYS[2], ARGV[2]) ~= ARGV[1] then return -1 end
if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
redis.call('HDEL', KEYS[2], ARGV[2])
redis.call('HSET', KEYS[3], ARGV[1], 'RELEASED')
redis.call('HINCRBY', KEYS[1], 'remaining', 1)
return 1
