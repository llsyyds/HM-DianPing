package com.hmdp.utils;

import net.sf.jsqlparser.expression.operators.arithmetic.Concat;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.ID_PREFIX;
import static com.hmdp.utils.RedisConstants.KEY_PREFIX;

/**
 * @author lls
 * @date 2022-2022/9/30-15:12
 */
public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //初始化Lua脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    //类加载时直接初始化完成
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unLock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程表示---->注意：这里的uuid用来区分不同jvm的threadId，线程id用来区分同一jvm的threadId
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId + "", timeoutSec, TimeUnit.SECONDS);
        //防止拆箱引发空指针异常 Boolean->boolean(拆箱)，此时如果success为null就会有问题。
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
        //调用lua脚本，保证判断标示是否一致和释放锁这两个操作是原子性，避免极端情况下发生线程安全问题--->因为下面变成一条完整的命令，会一起执行。
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX+Thread.currentThread().getId());
        /*//获取线程标示
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        //获取锁中的标示
        String Id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //判断标示是否一致
        if (threadId.equals(Id)) {
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }*/
    }
}
