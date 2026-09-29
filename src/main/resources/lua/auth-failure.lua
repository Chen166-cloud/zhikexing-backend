local cooldown = redis.call('PTTL', KEYS[2])
if cooldown > 0 then return math.ceil(cooldown / 1000) end
local failures = redis.call('INCR', KEYS[1])
if failures == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
if failures >= tonumber(ARGV[1]) then
    redis.call('DEL', KEYS[1])
    redis.call('SET', KEYS[2], '1', 'EX', ARGV[3])
    return tonumber(ARGV[3])
end
return 0
