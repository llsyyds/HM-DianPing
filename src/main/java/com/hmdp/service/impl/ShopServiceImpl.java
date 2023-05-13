package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //缓存击穿-互斥锁
        //Shop shop = queryWithMutex(id);

        //缓存击穿-逻辑过期时间+互斥锁
        //Shop shop = queryWithLogicalExpire(id);
        //Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);
        if (shop==null){
            return Result.fail("店铺信息不存在");
        }
        return Result.ok(shop);
    }

    //建立线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //封装缓存击穿-逻辑过期时间+互斥锁
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY+id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判读在redis中是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3.不存在,直接返回,注意：这里是针对的是热点key的应用场景，即那些热点key会在售卖之前就会被设置逻辑过期时间
            // 并直接上传到redis缓存中（相当于热点key的预热），此时如果在redis中找不到就是没有，所以不用设计缓存穿透的解决方案！！！
            return null;
        }
       //4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1.未过期，直接返回店铺信息
            return shop;
        }
        //6.已过期，需要重建缓存
        //6.1获得互斥锁
        String lockKey = LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否获取锁成功
        if(isLock){
            //6.3开启另一个线程，进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
               try {
                   //重建缓存，这边逻辑过期时间设为20秒，这是为了测试用的，实际会设定为更长。
                   this.saveShop2Redis(id, 20L);
               }catch (Exception e){
                   throw new RuntimeException(e);
               }finally {
                   unLock(lockKey);
               }
            });
        }
        return shop;
    }

    //封装缓存击穿-互斥锁
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY+id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判读在redis中是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //注意：空值也是不等于null的
        if(shopJson!=null){
            //返回一个错误信息
            return null;
        }
        //4.实现缓存重建
        //4.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY+id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取锁成功
            if(!isLock){
                //4.3失败，则休眠并且重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4获取锁成功
            shop = getById(id);
            //模拟重建的延迟,测试用的,测试完直接注释掉
            //Thread.sleep(200);
            //5.数据库中也查不到，返回错误
            if (shop==null) {
                //这里避免缓存穿透，将空值写入redis中
                stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //6.存在就将其写入redis中，方便下次查询
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }catch (InterruptedException e){
            throw new RuntimeException();
        }finally {
            //7.释放锁
            unLock(lockKey);
        }
        return shop;
    }

    //封装缓存穿透
   public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY+id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判读在redis中是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3. 存在
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //注意：空值也是不等于null的
        if(shopJson!=null){
            //返回一个错误信息
            return null;
        }
        //4.不存在，则从数据库中查
        Shop shop = getById(id);
        //5.数据库中也查不到，返回错误
        if (shop==null) {
            //这里避免缓存穿透，将空值写入redis中
            stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //6.存在就将其写入redis中，方便下次查询
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }


    //封装带有逻辑过期时间字段的shop对象--->热点key的预热流程
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询数据库
        Shop shop = getById(id);
        //模拟重建的延迟,测试用的,测试完直接注释掉
        Thread.sleep(200);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id, JSONUtil.toJsonStr(redisData));
    }

    //这里尝试获取锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1",LOCK_SHOP_TTL, TimeUnit.SECONDS);
        //这里直接返回flag可能会因为其拆箱发生空指针问题
        return BooleanUtil.isTrue(flag);
    }
    //这里删除锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    //这里的事务一般是有管理员去操作的，客户端没有操作权限
    @Override
    @Transactional//保证更新数据库和删除缓存是原子性的,如果二者出现异常会回滚
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id==null) {
            return Result.fail("id不能为空");
        }
        //1.更新数据库
        boolean b = updateById(shop);
        if (!b){
            return Result.fail("请确保原本存在此商铺");
        }
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        //3.返回
        return Result.ok();
    }

    //实现附近商铺功能
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //先判断是否需要根据坐标查询
        if(x==null||y==null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int from = (current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current*SystemConstants.DEFAULT_PAGE_SIZE;

        String key = SHOP_GEO_KEY+typeId;
        //这里获取的是0-end的所有数据
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        //解析出商铺id
        if(results==null){
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        //防止skip跳过后，id里面是空的，爆异常
        if(list.size()<=from){
            return Result.ok(Collections.emptyList());
        }
        Map<String,Distance> distanceMap = new HashMap<>(list.size());
        //截取from-end的数据
        List<Long> ids = new ArrayList<>(list.size());
        list.stream().skip(from).forEach(result->{
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        //根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }


}
