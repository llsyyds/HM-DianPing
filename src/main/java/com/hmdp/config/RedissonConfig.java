package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author lls
 * @date 2022-2022/10/2-9:19
 */
@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient(){
        //配置
        Config config = new Config();
        //注意：这个地址里面一定要写redis代表其是redis协议
        config.useSingleServer().setAddress("redis://121.4.219.216:6379").setPassword("gzpu44ry");
        //创建Redisson对象
        return Redisson.create(config);
    }
}
