-- Read-only checks; SET @campaign_id='...'; in the same connection beforehand.
-- The owner HTTP reconciliation and this query are separate snapshots: only
-- interpret final equality after all submission and asynchronous work stops.
SELECT c.id,c.capacity,c.remaining,
       (SELECT COUNT(*) FROM trial_order o WHERE o.campaign_id=c.id) AS confirmed_orders,
       c.capacity=c.remaining+(SELECT COUNT(*) FROM trial_order o WHERE o.campaign_id=c.id) AS stock_conserved,
       (SELECT COUNT(*) FROM trial_order o WHERE o.campaign_id=c.id)<=c.capacity AS no_oversell,
       (SELECT COUNT(*) FROM trial_claim_request r WHERE r.campaign_id=c.id AND r.status IN ('PENDING','RESERVED')) AS in_flight,
       (SELECT COUNT(*) FROM trial_claim_request r WHERE r.campaign_id=c.id AND r.release_pending=1) AS compensation_pending
FROM trial_campaign c WHERE c.id=@campaign_id;

SELECT status,COALESCE(reason,'') AS reason,COUNT(*) AS count
FROM trial_claim_request WHERE campaign_id=@campaign_id GROUP BY status,reason;

-- All of the following issue counts must be zero.
SELECT 'duplicate_order_per_actor' AS issue,COUNT(*) AS violations FROM
 (SELECT actor_id FROM trial_order WHERE campaign_id=@campaign_id GROUP BY actor_id HAVING COUNT(*)>1) x
UNION ALL
SELECT 'duplicate_order_per_request',COUNT(*) FROM
 (SELECT request_id FROM trial_order WHERE campaign_id=@campaign_id GROUP BY request_id HAVING COUNT(*)>1) x
UNION ALL
SELECT 'duplicate_claim_per_actor',COUNT(*) FROM
 (SELECT actor_id FROM trial_claim_request WHERE campaign_id=@campaign_id GROUP BY actor_id HAVING COUNT(*)>1) x
UNION ALL
SELECT 'duplicate_idempotency_key',COUNT(*) FROM
 (SELECT workspace_id,actor_id,source,client_request_id FROM trial_claim_request
  WHERE campaign_id=@campaign_id GROUP BY workspace_id,actor_id,source,client_request_id HAVING COUNT(*)>1) x
UNION ALL
SELECT 'succeeded_without_order',COUNT(*) FROM trial_claim_request r LEFT JOIN trial_order o ON o.request_id=r.id
 WHERE r.campaign_id=@campaign_id AND r.status='SUCCEEDED' AND o.id IS NULL
UNION ALL
SELECT 'order_without_succeeded_matching_claim',COUNT(*) FROM trial_order o LEFT JOIN trial_claim_request r ON r.id=o.request_id
 WHERE o.campaign_id=@campaign_id AND (r.id IS NULL OR r.status<>'SUCCEEDED' OR r.campaign_id<>o.campaign_id OR r.actor_id<>o.actor_id)
UNION ALL
SELECT 'nonzero_amount',COUNT(*) FROM trial_order WHERE campaign_id=@campaign_id AND amount_cent<>0;

-- Exact DB timings, nearest-rank percentiles. Successful-order timestamp is immutable.
WITH timing AS (
 SELECT TIMESTAMPDIFF(MICROSECOND,r.created_at,o.created_at)/1000.0 AS latency_ms,
        ROW_NUMBER() OVER (ORDER BY TIMESTAMPDIFF(MICROSECOND,r.created_at,o.created_at)) AS rn,
        COUNT(*) OVER () AS n
 FROM trial_claim_request r JOIN trial_order o ON o.request_id=r.id
 WHERE r.campaign_id=@campaign_id AND r.status='SUCCEEDED'
)
SELECT COUNT(*) AS successful_orders,MIN(latency_ms) AS min_ms,
       MAX(CASE WHEN rn=CEIL(n*.95) THEN latency_ms END) AS p95_ms,
       MAX(CASE WHEN rn=CEIL(n*.99) THEN latency_ms END) AS p99_ms,
       MAX(latency_ms) AS max_ms FROM timing;

-- Actual committed orders per wall-clock second, not accepted HTTP requests.
SELECT FLOOR(UNIX_TIMESTAMP(created_at)) AS epoch_second,COUNT(*) AS committed_orders
FROM trial_order WHERE campaign_id=@campaign_id GROUP BY epoch_second ORDER BY epoch_second;
