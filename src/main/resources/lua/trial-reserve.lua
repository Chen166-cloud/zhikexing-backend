-- All KEYS share {campaignId}. No expiry: a missing receipt is not a rollback proof.
-- KEYS: campaign, actor->request, request->result. ARGV: requestId, actorId.
local previous = redis.call('HGET', KEYS[3], ARGV[1])
if previous then return previous end
if redis.call('EXISTS', KEYS[1]) == 0 then return 'NOT_READY' end
if redis.call('HGET', KEYS[1], 'state') == 'PAUSED' then return 'PAUSED' end
if redis.call('HGET', KEYS[1], 'state') ~= 'LIVE' then return 'NOT_READY' end
local tm = redis.call('TIME')
local now = tonumber(tm[1]) * 1000 + math.floor(tonumber(tm[2]) / 1000)
if now < tonumber(redis.call('HGET', KEYS[1], 'start')) then return 'NOT_STARTED' end
if now >= tonumber(redis.call('HGET', KEYS[1], 'end')) then return 'ENDED' end
local holder = redis.call('HGET', KEYS[2], ARGV[2])
-- Partial cache loss: this actor's reservation exists but its receipt vanished.
if holder == ARGV[1] then return 'NOT_READY' end
if holder then return 'DUPLICATE' end
if tonumber(redis.call('HGET', KEYS[1], 'remaining')) <= 0 then return 'SOLD_OUT' end
redis.call('HINCRBY', KEYS[1], 'remaining', -1)
redis.call('HSET', KEYS[2], ARGV[2], ARGV[1])
redis.call('HSET', KEYS[3], ARGV[1], 'RESERVED')
return 'RESERVED'
