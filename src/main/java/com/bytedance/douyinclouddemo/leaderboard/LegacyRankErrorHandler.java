package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.LinkedHashMap;
import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=RankController.class)
public class LegacyRankErrorHandler {
    @ExceptionHandler(RankValidationException.class)
    public ResponseEntity<Map<String,Object>> validation(RankValidationException e){return response(400,10002,"参数不合法",e.getErrors());}
    @ExceptionHandler({JsonProcessingException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String,Object>> json(Exception e){return response(400,10001,"参数不合法",null);}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> failed(Exception e){return response(200,1000,"获取排行榜失败",null);}
    static ResponseEntity<Map<String,Object>> response(int http,int code,String message,Object data){Map<String,Object> body=new LinkedHashMap<>();body.put("errCode",code);body.put("errMsg",message);body.put("data",data);return ResponseEntity.status(http).body(body);}
}
