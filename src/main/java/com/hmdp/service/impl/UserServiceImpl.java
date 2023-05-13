package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.qcloudsms.SmsSingleSender;
import com.github.qcloudsms.SmsSingleSenderResult;
import com.github.qcloudsms.httpclient.HTTPException;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;


import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到redis中
       stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL,TimeUnit.MINUTES);
        //5.发生验证码到手机--->之前代码:log.debug("发生短信验证码成功，验证码：{}", code);
        int appId = 1400740715;
        String appKey = "95bce5c551952590b4a71737eaf484d2";
        int templateId = 1566462;
        String smsSign = "玉麒麟个人网站";
        //验证码的有效时间
        Integer effectiveTime = 1;
        try {
            String[] params = {code, Integer.toString(effectiveTime)}; //短信中的参数
            SmsSingleSender ssender = new SmsSingleSender(appId,appKey);
            SmsSingleSenderResult result = ssender.sendWithParam("86",phone,templateId,
                    params,smsSign,"","");
        }catch (JSONException e){
            e.printStackTrace();
        }catch (IOException e){
            e.printStackTrace();
        } catch (HTTPException e) {
            e.printStackTrace();
        }
        log.debug("发生短信验证码成功，验证码：{}", code);
        //返回OK
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //1.1.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //2.从redis中获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if(cacheCode==null){
            return Result.fail("没有通过该手机获取验证码？请重新操作");
        }
        String code = loginForm.getCode();
        if(!cacheCode.equals(code)){
            //3.不一致，报错
            return Result.fail("验证码错误");
        }
        //4.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //5.判断用户是否存在
        if(user==null){
            //6.不存在，创建新用户
            user = createUserWithPhone(phone);
        }

        //7.保存用户信息到redis中
        //7.1.随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //7.2.将user转为HashMap
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //这里需要将userDTO里面的字段值的类型都转换成string
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fileName, fileValue) -> fileValue.toString()));
        //7.3.存储
        String tokenKey = LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //7.4.设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL,TimeUnit.MINUTES);
        //这里把token返回给前端，其会被保存在sessionStorage(在一个会话结束时，里面的数据将会被清除)中，发送请求时携带过来。
        return Result.ok(token);
    }

    //签到功能业务
    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDate now = LocalDate.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY+userId+keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //写入redis的bigmap中
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth-1, true);
        return Result.ok();
    }

    //连续签到业务
    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDate now = LocalDate.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY+userId+keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取本月截止今天为止的连接签到记录-->bitfield sign:1018:202210 get u13 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if(result==null||result.isEmpty()){
            return Result.ok(0);
        }
        //获取十进制数字
        Long num = result.get(0);
        if(num==0||num==null){
            return Result.ok(0);
        }
        //统计连续签到天数
        int count = 0;
        while (true){
            //与1做与运算，得到最后一个bit位，判断是否为0
            if((num&1)==0){
                break;
            }else {
                //不会0，说明已签到，联系天数加1
                count++;
            }
            //比较完后移出最后一个bit位
            num>>>=1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //2.保存用户
        save(user);
        return user;
    }


}
