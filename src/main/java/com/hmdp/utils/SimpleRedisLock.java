package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SimpleRedisLock implements ILock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    // 静态代码块，类加载的时候会被执行一次，只有一次脚本的IO
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeOutSec) {
        // 获取当前线程的标识
        String threadId = ID_PREFIX + Thread.currentThread().threadId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeOutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().threadId());

    }


//    @Override
//    public void unLock() {
//        // 获取当前线程的标识
//        String threadId = ID_PREFIX + Thread.currentThread().threadId();
//        // 获取锁中的标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        // 判断标识是否一致
//        if (threadId.equals(id)) {
//            // 释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//
//    }
}
