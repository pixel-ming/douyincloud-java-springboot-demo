package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class RankErrorHandler {
    @ExceptionHandler(RankException.class)
    public ResponseEntity<Map<String,Object>> business(RankException e) { return response(e.getHttpStatus(),e.getCode(),e.getMessage()); }
    @ExceptionHandler({JsonProcessingException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String,Object>> badJson(Exception e) { return response(400,40001,"请求正文格式错误"); }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String,Object>> database(DataAccessException e) {
        log.error("排行榜数据库操作失败：exception={}",e.getClass().getSimpleName());return response(503,50301,"排行榜暂不可用");
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> unexpected(Exception e) {
        log.error("排行榜请求失败：exception={}",e.getClass().getSimpleName());return response(503,50301,"排行榜暂不可用");
    }
    private ResponseEntity<Map<String,Object>> response(int status,int code,String message) {
        Map<String,Object> body=new LinkedHashMap<>();body.put("errCode",code);body.put("errMsg",message);body.put("data",null);
        return ResponseEntity.status(status).body(body);
    }
}
