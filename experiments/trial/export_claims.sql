-- Run only against the isolated benchmark database after admission has stopped
-- and pending/reserved/release_pending have drained. Set @campaign_id first.
-- mysql --batch --raw emits one TSV table consumed by analyze_db.py.
-- updated_at is a terminal timestamp only after all compensation has finished;
-- for successful orders use immutable trial_order.created_at instead.
SELECT r.id AS request_id, r.actor_id, r.source, r.client_request_id,
       r.status, COALESCE(r.reason,'') AS reason, r.attempts, r.release_pending,
       c.capacity, c.remaining,
       UNIX_TIMESTAMP(r.created_at) AS request_epoch_s,
       UNIX_TIMESTAMP(r.updated_at) AS terminal_epoch_s,
       COALESCE(o.id,'') AS order_id,
       COALESCE(CAST(UNIX_TIMESTAMP(o.created_at) AS CHAR),'') AS order_epoch_s,
       COALESCE(CAST(TIMESTAMPDIFF(MICROSECOND,r.created_at,o.created_at) AS CHAR),'') AS order_latency_us,
       CASE WHEN r.status IN ('SUCCEEDED','REJECTED')
            THEN TIMESTAMPDIFF(MICROSECOND,r.created_at,r.updated_at)
            ELSE NULL END AS terminal_latency_us,
       COALESCE(CAST(o.actor_id AS CHAR),'') AS order_actor_id,
       COALESCE(o.campaign_id,'') AS order_campaign_id,
       r.campaign_id,
       COALESCE(CAST(o.amount_cent AS CHAR),'') AS amount_cent
FROM trial_claim_request r
JOIN trial_campaign c ON c.id=r.campaign_id
LEFT JOIN trial_order o ON o.request_id=r.id
WHERE r.campaign_id=@campaign_id
ORDER BY r.created_at,r.id;
