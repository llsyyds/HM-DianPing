package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private IShopTypeService typeService;

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void test() {
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();
        System.out.println(typeList);
    }

    //向redis中设置热点key
    @Test
    void test01() throws InterruptedException {
        shopService.saveShop2Redis(1L, 10L);
    }

    //对redis中
    @Test
    void test03() {
        Shop shop = shopService.getById(1);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void test04() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = "+(end-begin));
    }

    //进行附近商铺查询前提-->导入店铺数据到redis中的GEO中
    @Test
    void test05(){
        List<Shop> list = shopService.list();
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY+typeId;
            //返回用类型商铺的集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    //测试百万数据统计
    @Test
    void testHyperLogLog(){
        String[] values = new String[1000];
        int j=0;
        for (int i=0;i<1000000;i++){
            j =i%1000;
            values[j]="user_"+i;
            if(j==999){
                stringRedisTemplate.opsForHyperLogLog().add("h12", values);
            }
        }
        Long count = stringRedisTemplate.opsForHyperLogLog().size("h12");
        System.out.println(count);
    }
}
/*
        [
        ShopType(id=1, name=美食, icon=/types/ms.png, sort=1, createTime=2021-12-22T20:17:47, updateTime=2021-12-23T11:24:31),
        ShopType(id=2, name=KTV, icon=/types/KTV.png, sort=2, createTime=2021-12-22T20:18:27, updateTime=2021-12-23T11:24:31),
        ShopType(id=3, name=丽人·美发, icon=/types/lrmf.png, sort=3, createTime=2021-12-22T20:18:48, updateTime=2021-12-23T11:24:31),
        ShopType(id=10, name=美睫·美甲, icon=/types/mjmj.png, sort=4, createTime=2021-12-22T20:21:46, updateTime=2021-12-23T11:24:31),
        ShopType(id=5, name=按摩·足疗, icon=/types/amzl.png, sort=5, createTime=2021-12-22T20:19:27, updateTime=2021-12-23T11:24:31),
        ShopType(id=6, name=美容SPA, icon=/types/spa.png, sort=6, createTime=2021-12-22T20:19:35, updateTime=2021-12-23T11:24:31),
        ShopType(id=7, name=亲子游乐, icon=/types/qzyl.png, sort=7, createTime=2021-12-22T20:19:53, updateTime=2021-12-23T11:24:31),
        ShopType(id=8, name=酒吧, icon=/types/jiuba.png, sort=8, createTime=2021-12-22T20:20:02, updateTime=2021-12-23T11:24:31),
        ShopType(id=9, name=轰趴馆, icon=/types/hpg.png, sort=9, createTime=2021-12-22T20:20:08, updateTime=2021-12-23T11:24:31),
        ShopType(id=4, name=健身运动, icon=/types/jsyd.png, sort=10, createTime=2021-12-22T20:19:04, updateTime=2021-12-23T11:24:31)
        ]
*/



















