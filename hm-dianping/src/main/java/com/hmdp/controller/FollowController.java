package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;

    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long bloggerId,
                         @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(bloggerId, isFollow);
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long bloggerId) {
        return followService.isFollow(bloggerId);
    }

    @GetMapping("/common/{id}")
    public Result commonFollow(@PathVariable("id") Long bloggerId) {
        return followService.commonFollow(bloggerId);
    }
}
