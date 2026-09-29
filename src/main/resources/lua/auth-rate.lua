-- Fixed window: denied attempts never extend the window or the cooldown.
if #KEYS == 2 then
    local cooldown = redis.call('PTTL', KEYS[2])
    if cooldown > 0 then return math.ceil(cooldown / 1000) end
end
local count = tonumber(redis.call('GET', KEYS[1]) or '0')
if count >= tonumber(ARGV[1]) then
    return math.max(1, math.ceil(redis.call('PTTL', KEYS[1]) / 1000))
end
count = redis.call('INCR', KEYS[1])
if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
return 0
