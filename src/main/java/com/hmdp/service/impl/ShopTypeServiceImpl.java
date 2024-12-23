package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {

        String key = CACHE_TYPE_KEY;
        // 1.从redis查询商铺类型缓存
        List<String> shopTypes = stringRedisTemplate.opsForList().range(key, 0, -1);
        // 2.判断缓存是否存在
        if (shopTypes != null && !shopTypes.isEmpty()) {
            List<ShopType> tmp = new ArrayList<>();
            for(String type : shopTypes) {
                ShopType shopType = JSONUtil.toBean(type, ShopType.class);
                tmp.add(shopType);
            }
            return Result.ok(tmp);
        }
        // 3.缓存不存在，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        // 4.不存在，返回错误
        if (typeList == null) {
            return Result.fail("店铺类型不存在");
        }
        // 5.存在，数据放入redis
        for (ShopType type : typeList){
            String jsonStr = JSONUtil.toJsonStr(type);
            shopTypes.add(jsonStr);
        }
        stringRedisTemplate.opsForList().leftPushAll(key, shopTypes);

        return null;
    }
}
