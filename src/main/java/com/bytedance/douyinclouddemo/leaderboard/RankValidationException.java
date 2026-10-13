package com.bytedance.douyinclouddemo.leaderboard;

import lombok.Getter;
import java.util.Collections;
import java.util.Map;

@Getter
public class RankValidationException extends RuntimeException {
    private final Map<String,String> errors;
    public RankValidationException(String field,String message){super("参数不合法");errors=Collections.singletonMap(field,message);}
}
