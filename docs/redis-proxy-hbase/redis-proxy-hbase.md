
# camellia-redis-proxy-hbase
## 简介  
基于camellia-redis、camellia-hbase、camellia-redis-proxy开发   
目前实现了zset相关的命令，可以实现自动的冷热数据分离（冷数据存hbase，热数据存redis）  
1.0.20版本开始，camellia-redis-proxy-hbase进行了重构，且和老版本不兼容，以下内容均为重构后版本的描述    

## 原理
zset作为redis中的有序集合，由key、score、value三部分组成，其中value部分在某些场景下可能占用较大的字节数，但是却不会经常访问（比如只要访问score大于某个数值的value集合）     
为了减少redis的内存占用，我们通过将value部分从zset里抽离出来，zset的value部分本身只存一个索引，通过该索引可以从其他存储结构（我们这里选择了hbase）获取原始的value值    

### 写操作
* 当zadd一个超过阈值的value到某一个zset的key里的时候，会将value计算出一个索引，计算方法value_ref_key=md5(key)+md5(value)，实际的zset仅会保存value_ref_key，原始value会以简单k-v的形式保存在hbase里  
* 原始value除了持久化在hbase里之外，还会同步写到redis里（以一个简单的k-v形式存储，并搭配一个较短的ttl），通过这种方式来加快读取操作  
* 对于zrem/zremrangebyrank等删除操作，会先range出需要删除的value，随后再删除zset里的索引信息以及redis/hbase里的原始value信息  
* 如果调用了del命令，同样会先range出所有需要删除的原始value，再删除zset本身这个key
* 关于异步刷hbase：对于任何hbase的写操作，支持开启异步模式（默认开启），即写完redis立即返回，hbase的写操作（put/delete）会进入一个异步内存队列，然后批量刷到hbase，由于原始value会写一份到redis（较短ttl，从而降低redis内存使用量），因此异步写hbase不会导致写完后读不到      

### 读操作       
* 当调用zrange等读命令时，先直接从redis查询zset数据，查询出来value之后，判断是否是一个索引还是原始数据，如果是索引，则先去redis查询，查不到则穿透到hbase，若穿透到hbase，会回一写一份到redis（较短ttl）  
* 关于频控：当忽然有大量的读操作的时候，如果都没有命中redis，会穿透到hbase，为了避免hbase瞬间接收太多的读流量被打挂，支持开启单机的频控（默认关闭），频控方式进行降级是有损的，请结合业务特征按需配置  

### 配置
* 所有的配置参考RedisHBaseConfiguration（配置文件是：camellia-redis-proxy.properties）

### 监控
* 监控数据通过RedisHBaseMonitor类进行获取

### 服务依赖
* 依赖redis（支持redis-standalone/redis-sentinel/redis-cluster）和hbase  
* hbase建表语句（表名可以自行修改，在camellia-redis-proxy.properties修改配置即可）：
```
create 'nim:nim_camellia',{NAME=>'d',VERSIONS=>1,BLOCKCACHE=>true,BLOOMFILTER=>'ROW',COMPRESSION=>'LZO',TTL=>'5184000'},{NUMREGIONS => 12 , SPLITALGO => 'UniformSplit'}
```

## maven依赖
```
<dependency>
    <groupId>com.netease.nim</groupId>
    <artifactId>camellia-redis-proxy-hbase-spring-boot-starter</artifactId>
    <version>a.b.c</version>
</dependency>
```

## 支持的命令
```
##数据库
PING,AUTH,QUIT,EXISTS,DEL,TYPE,EXPIRE,
EXPIREAT,TTL,PEXPIRE,PEXPIREAT,PTTL,
##有序集合
ZADD,ZINCRBY,ZRANK,ZCARD,ZSCORE,ZCOUNT,ZRANGE,ZRANGEBYSCORE,ZRANGEBYLEX,
ZREVRANK,ZREVRANGE,ZREVRANGEBYSCORE,ZREVRANGEBYLEX,ZREM,
ZREMRANGEBYRANK,ZREMRANGEBYSCORE,ZREMRANGEBYLEX,ZLEXCOUNT,

```


### 更多示例和源码
[示例源码](/camellia-samples/camellia-redis-proxy-hbase-samples)
