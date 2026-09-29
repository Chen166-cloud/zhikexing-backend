-- Standalone Redis only: old token keys are derived from this user's bounded index.
-- KEYS: account session ZSET, new token hash.
-- ARGV: token, user id, username, nickname, TTL ms, session cap, token prefix.
local members = redis.call('ZRANGE', KEYS[1], 0, -1)
local last = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
local now = redis.call('TIME')
local created = tonumber(now[1]) * 1000000 + tonumber(now[2])
-- Keep creation order even if two logins have the same timestamp.
if #last > 0 then created = math.max(created, tonumber(last[2]) + 1) end

for _, member in ipairs(members) do
    if redis.call('EXISTS', ARGV[7] .. member) == 0 then
        redis.call('ZREM', KEYS[1], member)
    end
end

redis.call('HSET', KEYS[2], 'id', ARGV[2], 'userName', ARGV[3], 'nickName', ARGV[4])
redis.call('PEXPIRE', KEYS[2], ARGV[5])
redis.call('ZADD', KEYS[1], created, ARGV[1])

local overflow = redis.call('ZCARD', KEYS[1]) - tonumber(ARGV[6])
if overflow > 0 then
    local oldest = redis.call('ZRANGE', KEYS[1], 0, overflow - 1)
    for _, member in ipairs(oldest) do
        redis.call('DEL', ARGV[7] .. member)
        redis.call('ZREM', KEYS[1], member)
    end
end
redis.call('PEXPIRE', KEYS[1], ARGV[5])
return 1
