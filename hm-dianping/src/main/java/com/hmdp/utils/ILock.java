package com.hmdp.utils;

public interface ILock {
    public boolean tryLock(Long expireTime);

    public void unlock();
}
