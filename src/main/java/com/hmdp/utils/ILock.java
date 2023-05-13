package com.hmdp.utils;

/**
 * @author lls
 * @date 2022-2022/9/30-15:10
 */
public interface ILock {

    boolean tryLock(long timeoutSec);

    void unLock();
}
