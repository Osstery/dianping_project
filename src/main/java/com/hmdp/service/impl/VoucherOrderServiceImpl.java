package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    // 静态代码块，类加载的时候会被执行一次，只有一次脚本的IO
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct //当前类初始化完毕后执行
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if (!isLock) {
            //获取锁失败，返回失败或者重试
            log.error("不允许重复下单");
            return;
        }
        try {

            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }

    }

    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        // 2.判断结果是否为零
        int r = result.intValue();
        if (r != 0){
            // 2.1 不为0，代表没有购买资格
            return Result.fail(r==1?"库存不足" : "不能重复下单");
        }

        // 2.2 为0，代表有购买资格，把下单信息保存到阻塞队列
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 用户id
        voucherOrder.setUserId(userId);
        // 代金券id
        voucherOrder.setVoucherId(voucherId);
        // 放入阻塞队列
        orderTasks.add(voucherOrder);
        //拿到当前对象的代理对象(事务）
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 3. 返回订单id
        return Result.ok(orderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 尚未开始
//            return Result.fail("秒杀尚未开始");
//        }
//        // 3.判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            // 已经结束
//            return Result.fail("秒杀已经结束");
//        }
//
//        // 4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            // 库存不足
//            return Result.fail("库存不足");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//        //事务会导致锁失效：先释放锁再提交事务，可能导致线程安全问题
//        //动态代理负责维护事务的生命周期，包括事务的开启、提交和回滚
//        //如果直接使用目标对象，Spring将无法在方法调用前后执行必要的事务管理操作，因为它依赖于代理机制中插入这些事务管理步骤
////        synchronized(userId.toString().intern()) {//intern(): 实习生方法，字符串常量池中已经存在该字符串，则返回该字符串，否则创建该字符串，并返回该字符串
////            //拿到当前对象的代理对象(事务）
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//        //创建锁对象
////        ILock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //获取锁
//        boolean isLock = lock.tryLock();
//        //判断是否获取锁成功
//        if (!isLock) {
//            //获取锁失败，返回失败或者重试
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            //拿到当前对象的代理对象(事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            lock.unlock();
//        }
//    }

    // 同步代码块
    @Transactional // 牵涉到了订单的新增和库存的扣减，两张表操作，最好加上事务
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5. 一人一单
        Long userId = voucherOrder.getUserId();


        // 5.1 查询订单，判断该用户是否已经购买过该优惠券
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2 判断是否存在
        if (count > 0) {
            // 已经购买过
            log.error("用户已经购买过该优惠券");
            return;
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)// where id=? and stock>0
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足");
            return;
        }
        // 7.创建订单

        save(voucherOrder);

    }
}
