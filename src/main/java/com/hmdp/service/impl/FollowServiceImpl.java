package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    //点击关注按钮时进行的业务
    @Override
    public Result follow(Long followId, boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = "follows:"+userId;
        //注意：这里的isFollow是前端传过来的，点一下true，再点一下就是false
        if(isFollow){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followId);
            boolean success = save(follow);
            //将用户id作为key，关注对象作为value放入redis的set集合中
            if(success){
                stringRedisTemplate.opsForSet().add(key, followId.toString());
            }
        }else {
            boolean success = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followId));
            if(success){
                stringRedisTemplate.opsForSet().remove(key, followId.toString());
            }
        }
        return Result.ok();
    }

    //页面加载时发起请求判断该用户有没有关注这个博主
    @Override
    public Result isfollow(Long followId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followId).count();
        //返回给前端，让前端进行渲染
        return Result.ok(count>0);
    }

    //共同关注业务
    @Override
    public Result followCommons(Long id) {
        Long userId = UserHolder.getUser().getId();
        //当前用户
        String key = "follows:"+userId;
        //目标用户
        String key2 = "follows:"+id;
        //取共同关注
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        //解析id集合
        if(intersect==null||intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> commonIds = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //根据id查询用户
        List<UserDTO> users = userService.listByIds(commonIds)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
















