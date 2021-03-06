package com.netease.nim.camellia.redis.proxy.hbase.monitor;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.netease.nim.camellia.redis.proxy.util.ExecutorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 * Created by caojiajun on 2020/12/22
 */
public class RedisHBaseMonitor {

    private static final Logger logger = LoggerFactory.getLogger(RedisHBaseMonitor.class);
    private static ConcurrentHashMap<String, AtomicLong> map = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, AtomicLong> degradedMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Queue> queueMap = new ConcurrentHashMap<>();
    private static RedisHBaseStats redisHBaseStats = new RedisHBaseStats();
    static {
        ExecutorUtils.scheduleAtFixedRate(RedisHBaseMonitor::calc, 1, 1, TimeUnit.MINUTES);
    }

    public static void incr(String method, String desc) {
        String uniqueKey = method + "|" + desc;
        AtomicLong count = map.computeIfAbsent(uniqueKey, k -> new AtomicLong());
        count.incrementAndGet();
    }

    public static void incrDegraded(String desc) {
        degradedMap.computeIfAbsent(desc, k -> new AtomicLong()).incrementAndGet();
    }

    public static void register(String name, Queue queue) {
        queueMap.put(name, queue);
    }

    public static RedisHBaseStats getRedisHBaseStats() {
        return redisHBaseStats;
    }

    public static JSONObject getStatsJson() {
        JSONObject monitorJson = new JSONObject();

        JSONArray statsJsonArray = new JSONArray();
        for (RedisHBaseStats.Stats stats : redisHBaseStats.getStatsList()) {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("method", stats.getMethod());
            jsonObject.put("desc", stats.getDesc());
            jsonObject.put("count", stats.getCount());
            statsJsonArray.add(jsonObject);
        }
        monitorJson.put("countStats", statsJsonArray);

        JSONArray statsJson2Array = new JSONArray();
        for (RedisHBaseStats.Stats2 stats2 : redisHBaseStats.getStats2List()) {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("method", stats2.getMethod());
            jsonObject.put("cacheHitPercent", stats2.getCacheHitPercent());
            jsonObject.put("count", stats2.getCount());
            statsJson2Array.add(jsonObject);
        }
        monitorJson.put("cacheHitStats", statsJson2Array);

        JSONArray queueStatsJsonArray = new JSONArray();
        for (RedisHBaseStats.QueueStats queueStats : redisHBaseStats.getQueueStatsList()) {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("queueName", queueStats.getQueueName());
            jsonObject.put("queueSize", queueStats.getQueueSize());
            queueStatsJsonArray.add(jsonObject);
        }
        monitorJson.put("queueStats", queueStatsJsonArray);

        JSONArray degradedStatsJsonArray = new JSONArray();
        for (RedisHBaseStats.DegradedStats degradedStats : redisHBaseStats.getDegradedStatsList()) {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("desc", degradedStats.getDesc());
            jsonObject.put("count", degradedStats.getCount());
            degradedStatsJsonArray.add(jsonObject);
        }
        monitorJson.put("degradedStats", degradedStatsJsonArray);
        return monitorJson;
    }

    private static void calc() {
        try {
            ConcurrentHashMap<String, AtomicLong> map = RedisHBaseMonitor.map;
            ConcurrentHashMap<String, AtomicLong> degradedMap = RedisHBaseMonitor.degradedMap;
            RedisHBaseMonitor.map = new ConcurrentHashMap<>();
            RedisHBaseMonitor.degradedMap = new ConcurrentHashMap<>();

            Map<String, Long> cacheHitMap = new HashMap<>();
            Map<String, Long> cacheMissMap = new HashMap<>();
            List<RedisHBaseStats.Stats> statsList = new ArrayList<>();
            for (Map.Entry<String, AtomicLong> entry : map.entrySet()) {
                RedisHBaseStats.Stats stats = new RedisHBaseStats.Stats();
                String[] split = entry.getKey().split("\\|");
                String method = split[0];
                String desc = split[1];
                stats.setMethod(method);
                stats.setDesc(desc);
                stats.setCount(entry.getValue().get());
                statsList.add(stats);
                if (desc.equals(OperationType.REDIS_ONLY.name())) {
                    cacheHitMap.put(method, entry.getValue().get());
                } else {
                    cacheMissMap.put(method, entry.getValue().get());
                }
            }
            List<RedisHBaseStats.Stats2> stats2List = new ArrayList<>();
            Set<String> methodSet = new HashSet<>();
            methodSet.addAll(cacheHitMap.keySet());
            methodSet.addAll(cacheMissMap.keySet());
            for (String method : methodSet) {
                RedisHBaseStats.Stats2 stats2 = new RedisHBaseStats.Stats2();
                stats2.setMethod(method);
                Long cacheHit = cacheHitMap.get(method);
                cacheHit = cacheHit == null ? 0 : cacheHit;
                Long cacheMiss = cacheMissMap.get(method);
                cacheMiss = cacheMiss == null ? 0 : cacheMiss;
                long total = cacheHit + cacheMiss;
                double cacheHitPercent;
                if (total == 0) {
                    cacheHitPercent = 0.0;
                } else {
                    cacheHitPercent = cacheHit / (total * 1.0);
                }
                stats2.setCount(total);
                stats2.setCacheHitPercent(cacheHitPercent);
                stats2List.add(stats2);
            }

            List<RedisHBaseStats.QueueStats> queueStatsList = new ArrayList<>();
            for (Map.Entry<String, Queue> entry : queueMap.entrySet()) {
                RedisHBaseStats.QueueStats queueStats = new RedisHBaseStats.QueueStats();
                queueStats.setQueueName(entry.getKey());
                queueStats.setQueueSize(entry.getValue().size());
                queueStatsList.add(queueStats);
            }

            List<RedisHBaseStats.DegradedStats> degradedStatsList = new ArrayList<>();
            for (Map.Entry<String, AtomicLong> entry : degradedMap.entrySet()) {
                RedisHBaseStats.DegradedStats degradedStats = new RedisHBaseStats.DegradedStats();
                degradedStats.setDesc(entry.getKey());
                degradedStats.setCount(entry.getValue().get());
                degradedStatsList.add(degradedStats);
            }

            RedisHBaseStats redisHBaseStats = new RedisHBaseStats();
            redisHBaseStats.setStatsList(statsList);
            redisHBaseStats.setStats2List(stats2List);
            redisHBaseStats.setQueueStatsList(queueStatsList);
            redisHBaseStats.setDegradedStatsList(degradedStatsList);

            RedisHBaseMonitor.redisHBaseStats = redisHBaseStats;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }
}
