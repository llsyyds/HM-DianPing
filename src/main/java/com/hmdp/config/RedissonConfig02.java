package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author lls
 * @date 2022-2022/10/2-19:18
 */
//这里可以搭建一个redis集群，没有密码的，有时间再去搞，现在走走源码就行
//@Configuration
public class RedissonConfig02 {
    @Bean
    public RedissonClient redissonClient(){
        //配置
        Config config = new Config();
        //注意：这个地址里面一定要写redis代表其是redis协议
        config.useSingleServer().setAddress("redis://121.4.219.216:6379");
        //创建Redisson对象
        return Redisson.create(config);
    }
    @Bean
    public RedissonClient redissonClient2(){
        //配置
        Config config = new Config();
        //注意：这个地址里面一定要写redis代表其是redis协议
        config.useSingleServer().setAddress("redis://121.4.219.216:6380");
        //创建Redisson对象
        return Redisson.create(config);
    }
    @Bean
    public RedissonClient redissonClient3(){
        //配置
        Config config = new Config();
        //注意：这个地址里面一定要写redis代表其是redis协议
        config.useSingleServer().setAddress("redis://121.4.219.216:6381");
        //创建Redisson对象
        return Redisson.create(config);
    }
}
