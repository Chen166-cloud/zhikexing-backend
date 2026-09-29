-- KEYS: token hash, account session ZSET.
-- ARGV: token, expected user id, operation, TTL ms, nickname.
if redis.call('HGET', KEYS[1], 'id') ~= ARGV[2] then return {} end

if ARGV[3] == 'logout' then
    redis.call('ZREM', KEYS[2], ARGV[1])
    redis.call('DEL', KEYS[1])
    return {}
end

-- A removed/expired session must never be recreated by a refresh or nickname update.
if not redis.call('ZSCORE', KEYS[2], ARGV[1]) then
    redis.call('DEL', KEYS[1])
    return {}
end

local user = redis.call('HMGET', KEYS[1], 'id', 'userName', 'nickName')
if ARGV[3] == 'nickname' then
    redis.call('HSET', KEYS[1], 'nickName', ARGV[5])
end
redis.call('PEXPIRE', KEYS[1], ARGV[4])
redis.call('PEXPIRE', KEYS[2], ARGV[4])
return user
