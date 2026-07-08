package com.prodigalgal.ircs.task.runtime;

import org.springframework.data.redis.core.script.DefaultRedisScript;

final class TaskProgressRedisScripts {

    static final DefaultRedisScript<Long> PAGE_DISCOVERED = new DefaultRedisScript<>(
            """
            redis.call('SADD', KEYS[1], ARGV[1])
            local previousStatus = redis.call('HGET', KEYS[2], 'status') or ''
            local previousMasterStatus = redis.call('HGET', KEYS[3], 'status') or ''
            local firstDiscovery = 0
            if previousStatus == '' or previousStatus == 'QUEUED' then
              firstDiscovery = 1
            end
            local detailScheduled = tonumber(ARGV[3]) or 0
            local pageStatus = 'DISCOVERED'
            if detailScheduled <= 0 then
              pageStatus = 'COMPLETED'
            end
            redis.call('HSET', KEYS[2],
              'masterTaskId', ARGV[7],
              'pageTaskId', ARGV[1],
              'pageNumber', ARGV[2],
              'detailScheduled', ARGV[3],
              'detailCompleted', '0',
              'detailSucceeded', '0',
              'detailFailed', '0',
              'status', pageStatus,
              'updatedAt', ARGV[6])
            if firstDiscovery == 1 then
              redis.call('HINCRBY', KEYS[3], 'detailScheduled', ARGV[3])
              redis.call('HINCRBY', KEYS[3], 'pageDiscovered', 1)
              if detailScheduled <= 0 then
                redis.call('HINCRBY', KEYS[3], 'pageCompleted', 1)
                redis.call('HINCRBY', KEYS[3], 'pageSucceeded', 1)
              end
            end
            local pageScheduled = tonumber(redis.call('HGET', KEYS[3], 'pageScheduled') or '0')
            local pageCompleted = tonumber(redis.call('HGET', KEYS[3], 'pageCompleted') or '0')
            local pageFailed = tonumber(redis.call('HGET', KEYS[3], 'pageFailed') or '0')
            local masterDetailScheduled = tonumber(redis.call('HGET', KEYS[3], 'detailScheduled') or '0')
            local detailCompleted = tonumber(redis.call('HGET', KEYS[3], 'detailCompleted') or '0')
            local detailFailed = tonumber(redis.call('HGET', KEYS[3], 'detailFailed') or '0')
            local masterStatus = previousMasterStatus
            if masterStatus ~= 'PAUSED'
              and masterStatus ~= 'STOPPING'
              and masterStatus ~= 'FAILED'
              and masterStatus ~= 'COMPLETED'
              and masterStatus ~= 'COMPLETED_WITH_ERRORS' then
              masterStatus = 'RUNNING'
              if pageScheduled > 0 and pageCompleted >= pageScheduled and detailCompleted >= masterDetailScheduled then
                if pageFailed > 0 or detailFailed > 0 then
                  masterStatus = 'FAILED'
                else
                  masterStatus = 'COMPLETED'
                end
              end
            end
            redis.call('HSET', KEYS[3],
              'masterTaskId', ARGV[7],
              'status', masterStatus,
              'updatedAt', ARGV[6])
            if ARGV[4] ~= '' then
              redis.call('HSET', KEYS[3], 'totalPages', ARGV[4])
            end
            if ARGV[5] ~= '' then
              redis.call('HSET', KEYS[3], 'totalItems', ARGV[5])
            end
            redis.call('ZADD', KEYS[4], ARGV[6], ARGV[7])
            if firstDiscovery == 1 then
              redis.call('XADD', KEYS[5], 'MAXLEN', '~', ARGV[8], '*',
                'event', 'page_discovered',
                'masterTaskId', ARGV[7],
                'pageTaskId', ARGV[1],
                'pageNumber', ARGV[2],
                'detailScheduled', ARGV[3])
              redis.call('HINCRBY', KEYS[6], ARGV[12], 1)
              if detailScheduled <= 0 then
                redis.call('XADD', KEYS[5], 'MAXLEN', '~', ARGV[8], '*',
                  'event', 'page_completed',
                  'masterTaskId', ARGV[7],
                  'pageTaskId', ARGV[1],
                  'pageNumber', ARGV[2],
                  'status', pageStatus)
                redis.call('HINCRBY', KEYS[6], ARGV[13], 1)
              end
              redis.call('EXPIRE', KEYS[6], ARGV[10])
              redis.call('ZADD', KEYS[7], ARGV[9], ARGV[9])
              redis.call('ZREMRANGEBYSCORE', KEYS[7], '-inf', tonumber(ARGV[9]) - tonumber(ARGV[11]))
              redis.call('EXPIRE', KEYS[7], math.ceil(tonumber(ARGV[11]) / 1000))
            end
            return firstDiscovery
            """,
            Long.class);

    static final DefaultRedisScript<Long> PAGE_FAILED = new DefaultRedisScript<>(
            """
            redis.call('SADD', KEYS[1], ARGV[1])
            local previousStatus = redis.call('HGET', KEYS[2], 'status') or ''
            local previousMasterStatus = redis.call('HGET', KEYS[3], 'status') or ''
            local firstFailure = 0
            if previousStatus ~= 'FAILED'
              and previousStatus ~= 'COMPLETED'
              and previousStatus ~= 'COMPLETED_WITH_ERRORS' then
              firstFailure = 1
            end
            redis.call('HSET', KEYS[2],
              'masterTaskId', ARGV[5],
              'pageTaskId', ARGV[1],
              'pageNumber', ARGV[2],
              'detailScheduled', '0',
              'detailCompleted', '0',
              'detailSucceeded', '0',
              'detailFailed', '0',
              'status', 'FAILED',
              'lastError', ARGV[3],
              'updatedAt', ARGV[4])
            if firstFailure == 1 then
              if previousStatus == '' or previousStatus == 'QUEUED' then
                redis.call('HINCRBY', KEYS[3], 'pageDiscovered', 1)
              end
              redis.call('HINCRBY', KEYS[3], 'pageCompleted', 1)
              redis.call('HINCRBY', KEYS[3], 'pageFailed', 1)
            end
            local pageScheduled = tonumber(redis.call('HGET', KEYS[3], 'pageScheduled') or '0')
            local pageCompleted = tonumber(redis.call('HGET', KEYS[3], 'pageCompleted') or '0')
            local masterDetailScheduled = tonumber(redis.call('HGET', KEYS[3], 'detailScheduled') or '0')
            local masterDetailCompleted = tonumber(redis.call('HGET', KEYS[3], 'detailCompleted') or '0')
            local masterStatus = previousMasterStatus
            if masterStatus ~= 'PAUSED'
              and masterStatus ~= 'STOPPING'
              and masterStatus ~= 'FAILED'
              and masterStatus ~= 'COMPLETED'
              and masterStatus ~= 'COMPLETED_WITH_ERRORS' then
              masterStatus = 'RUNNING'
              if pageScheduled > 0 and pageCompleted >= pageScheduled and masterDetailCompleted >= masterDetailScheduled then
                masterStatus = 'FAILED'
              end
            end
            redis.call('HSET', KEYS[3],
              'masterTaskId', ARGV[5],
              'status', masterStatus,
              'lastError', ARGV[3],
              'updatedAt', ARGV[4])
            redis.call('ZADD', KEYS[4], ARGV[4], ARGV[5])
            if firstFailure == 1 then
              redis.call('XADD', KEYS[5], 'MAXLEN', '~', ARGV[6], '*',
                'event', 'page_failed',
                'masterTaskId', ARGV[5],
                'pageTaskId', ARGV[1],
                'pageNumber', ARGV[2],
                'reason', ARGV[3])
              redis.call('HINCRBY', KEYS[6], ARGV[10], 1)
              redis.call('EXPIRE', KEYS[6], ARGV[8])
              redis.call('ZADD', KEYS[7], ARGV[7], ARGV[7])
              redis.call('ZREMRANGEBYSCORE', KEYS[7], '-inf', tonumber(ARGV[7]) - tonumber(ARGV[9]))
              redis.call('EXPIRE', KEYS[7], math.ceil(tonumber(ARGV[9]) / 1000))
            end
            return 1
            """,
            Long.class);

    static final DefaultRedisScript<Long> DETAIL_COMPLETED = new DefaultRedisScript<>(
            """
            local added = redis.call('SADD', KEYS[1], ARGV[1])
            if added == 0 then
              return 0
            end
            local completed = redis.call('HINCRBY', KEYS[2], 'detailCompleted', 1)
            redis.call('HINCRBY', KEYS[3], 'detailCompleted', 1)
            local failed = tonumber(redis.call('HGET', KEYS[2], 'detailFailed') or '0')
            local previousStatus = redis.call('HGET', KEYS[2], 'status') or ''
            if ARGV[2] == 'true' then
              redis.call('HINCRBY', KEYS[2], 'detailSucceeded', 1)
              redis.call('HINCRBY', KEYS[3], 'detailSucceeded', 1)
            else
              failed = redis.call('HINCRBY', KEYS[2], 'detailFailed', 1)
              redis.call('HINCRBY', KEYS[3], 'detailFailed', 1)
              redis.call('SADD', KEYS[5], ARGV[1])
              local errorSummary = ARGV[5]
              if ARGV[7] ~= '' then
                errorSummary = ARGV[7] .. ' :: ' .. errorSummary
              end
              redis.call('HSET', KEYS[6], ARGV[1], errorSummary)
              redis.call('HSET', KEYS[2], 'lastError', ARGV[5])
              redis.call('HSET', KEYS[3], 'lastError', ARGV[5])
            end
            local scheduled = tonumber(redis.call('HGET', KEYS[2], 'detailScheduled') or '0')
            local pageStatus = 'RUNNING'
            if scheduled > 0 and completed >= scheduled then
              if failed > 0 then
                pageStatus = 'COMPLETED_WITH_ERRORS'
              else
                pageStatus = 'COMPLETED'
              end
              if previousStatus ~= 'COMPLETED' and previousStatus ~= 'COMPLETED_WITH_ERRORS' then
                redis.call('HINCRBY', KEYS[3], 'pageCompleted', 1)
                if failed <= 0 then
                  redis.call('HINCRBY', KEYS[3], 'pageSucceeded', 1)
                end
              end
            end
            local pageScheduled = tonumber(redis.call('HGET', KEYS[3], 'pageScheduled') or '0')
            local pageCompleted = tonumber(redis.call('HGET', KEYS[3], 'pageCompleted') or '0')
            local pageFailed = tonumber(redis.call('HGET', KEYS[3], 'pageFailed') or '0')
            local masterDetailScheduled = tonumber(redis.call('HGET', KEYS[3], 'detailScheduled') or '0')
            local masterDetailCompleted = tonumber(redis.call('HGET', KEYS[3], 'detailCompleted') or '0')
            local masterDetailFailed = tonumber(redis.call('HGET', KEYS[3], 'detailFailed') or '0')
            local previousMasterStatus = redis.call('HGET', KEYS[3], 'status') or ''
            local masterStatus = previousMasterStatus
            if masterStatus ~= 'PAUSED'
              and masterStatus ~= 'STOPPING'
              and masterStatus ~= 'FAILED'
              and masterStatus ~= 'COMPLETED'
              and masterStatus ~= 'COMPLETED_WITH_ERRORS' then
              masterStatus = 'RUNNING'
              if pageScheduled > 0
                and pageCompleted >= pageScheduled
                and masterDetailCompleted >= masterDetailScheduled then
                if pageFailed > 0 or masterDetailFailed > 0 then
                  masterStatus = 'FAILED'
                else
                  masterStatus = 'COMPLETED'
                end
              end
            end
            redis.call('HSET', KEYS[2],
              'masterTaskId', ARGV[4],
              'pageTaskId', ARGV[6],
              'status', pageStatus,
              'updatedAt', ARGV[3])
            redis.call('HSET', KEYS[3],
              'masterTaskId', ARGV[4],
              'status', masterStatus,
              'updatedAt', ARGV[3])
            redis.call('ZADD', KEYS[4], ARGV[3], ARGV[4])
            redis.call('XADD', KEYS[7], 'MAXLEN', '~', ARGV[8], '*',
              'event', 'detail_completed',
              'masterTaskId', ARGV[4],
              'pageTaskId', ARGV[6],
              'detailTaskId', ARGV[1],
              'sourceVid', ARGV[7],
              'successful', ARGV[2])
            redis.call('HINCRBY', KEYS[8], ARGV[12], 1)
            if ARGV[2] == 'true' then
              redis.call('HINCRBY', KEYS[8], ARGV[13], 1)
            else
              redis.call('HINCRBY', KEYS[8], ARGV[14], 1)
            end
            if scheduled > 0
              and completed >= scheduled
              and previousStatus ~= 'COMPLETED'
              and previousStatus ~= 'COMPLETED_WITH_ERRORS' then
              redis.call('XADD', KEYS[7], 'MAXLEN', '~', ARGV[8], '*',
                'event', 'page_completed',
                'masterTaskId', ARGV[4],
                'pageTaskId', ARGV[6],
                'status', pageStatus)
              redis.call('HINCRBY', KEYS[8], ARGV[15], 1)
            end
            redis.call('EXPIRE', KEYS[8], ARGV[10])
            redis.call('ZADD', KEYS[9], ARGV[9], ARGV[9])
            redis.call('ZREMRANGEBYSCORE', KEYS[9], '-inf', tonumber(ARGV[9]) - tonumber(ARGV[11]))
            redis.call('EXPIRE', KEYS[9], math.ceil(tonumber(ARGV[11]) / 1000))
            return 1
            """,
            Long.class);

    static final DefaultRedisScript<Long> MASTER_DONE = new DefaultRedisScript<>(
            """
            local previousMasterStatus = redis.call('HGET', KEYS[1], 'status') or ''
            local nextStatus = ARGV[2]
            if previousMasterStatus == 'PAUSED'
              or previousMasterStatus == 'STOPPING'
              or previousMasterStatus == 'FAILED'
              or previousMasterStatus == 'COMPLETED'
              or previousMasterStatus == 'COMPLETED_WITH_ERRORS' then
              nextStatus = previousMasterStatus
            end
            redis.call('HSET', KEYS[1],
              'masterTaskId', ARGV[1],
              'status', nextStatus,
              'pageScheduled', ARGV[3],
              'pageCompleted', ARGV[4],
              'pageSucceeded', ARGV[5],
              'pageFailed', ARGV[6],
              'detailScheduled', ARGV[7],
              'detailCompleted', ARGV[8],
              'detailSucceeded', ARGV[9],
              'detailFailed', ARGV[10],
              'lastError', ARGV[11],
              'updatedAt', ARGV[12])
            redis.call('ZADD', KEYS[2], ARGV[12], ARGV[1])
            redis.call('XADD', KEYS[3], 'MAXLEN', '~', ARGV[13], '*',
              'event', 'master_done',
              'masterTaskId', ARGV[1],
              'status', nextStatus,
              'pageCompleted', ARGV[4],
              'detailCompleted', ARGV[8])
            return 1
            """,
            Long.class);

    static final DefaultRedisScript<Long> SCHEDULE_PAGE = new DefaultRedisScript<>(
            """
            local previousMasterStatus = redis.call('HGET', KEYS[2], 'status') or ''
            if previousMasterStatus == 'PAUSED'
              or previousMasterStatus == 'STOPPING'
              or previousMasterStatus == 'FAILED'
              or previousMasterStatus == 'COMPLETED_WITH_ERRORS' then
              return -1
            end
            local added = redis.call('SADD', KEYS[1], ARGV[3])
            if added == 0 then
              return 0
            end
            redis.call('HINCRBY', KEYS[2], 'pageScheduled', 1)
            redis.call('HSET', KEYS[2],
              'masterTaskId', ARGV[1],
              'status', 'RUNNING',
              'updatedAt', ARGV[4])
            redis.call('SADD', KEYS[4], ARGV[2])
            redis.call('HSET', KEYS[5],
              'masterTaskId', ARGV[1],
              'pageTaskId', ARGV[2],
              'pageNumber', ARGV[3],
              'detailScheduled', '0',
              'detailCompleted', '0',
              'detailSucceeded', '0',
              'detailFailed', '0',
              'status', 'QUEUED',
              'updatedAt', ARGV[4])
            redis.call('ZADD', KEYS[3], ARGV[4], ARGV[1])
            redis.call('XADD', KEYS[6], 'MAXLEN', '~', ARGV[5], '*',
              'event', 'page_scheduled',
              'masterTaskId', ARGV[1],
              'pageTaskId', ARGV[2],
              'pageNumber', ARGV[3])
            redis.call('HINCRBY', KEYS[7], ARGV[9], 1)
            redis.call('EXPIRE', KEYS[7], ARGV[7])
            redis.call('ZADD', KEYS[8], ARGV[6], ARGV[6])
            redis.call('ZREMRANGEBYSCORE', KEYS[8], '-inf', tonumber(ARGV[6]) - tonumber(ARGV[8]))
            redis.call('EXPIRE', KEYS[8], math.ceil(tonumber(ARGV[8]) / 1000))
            return tonumber(ARGV[3])
            """,
            Long.class);

    static final DefaultRedisScript<Long> ROLLBACK_SCHEDULED_PAGE = new DefaultRedisScript<>(
            """
            local removed = redis.call('SREM', KEYS[1], ARGV[3])
            if removed == 0 then
              return 0
            end
            local pageStatus = redis.call('HGET', KEYS[5], 'status') or ''
            if pageStatus ~= '' and pageStatus ~= 'QUEUED' then
              redis.call('SADD', KEYS[1], ARGV[3])
              return 0
            end
            redis.call('SREM', KEYS[4], ARGV[2])
            redis.call('DEL', KEYS[5])
            local scheduled = tonumber(redis.call('HGET', KEYS[2], 'pageScheduled') or '0')
            if scheduled > 0 then
              scheduled = redis.call('HINCRBY', KEYS[2], 'pageScheduled', -1)
            end
            if scheduled <= 0 then
              redis.call('HSET', KEYS[2], 'status', 'QUEUED')
            end
            redis.call('HSET', KEYS[2],
              'masterTaskId', ARGV[1],
              'updatedAt', ARGV[4])
            redis.call('ZADD', KEYS[3], ARGV[4], ARGV[1])
            return 1
            """,
            Long.class);

    private TaskProgressRedisScripts() {
    }
}
