package com.bytedance.douyinclouddemo.leaderboard;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

@Component
@RequiredArgsConstructor
public class CallbackVerifier {
    private final LeaderboardProperties properties;
    private final Clock clock;

    public LeaderboardProperties.App app(String alias) {
        LeaderboardProperties.App app=properties.getApps().get(alias);
        if(app==null || !app.isEnabled()) throw new RankException(503,50303,"礼物接入尚未启用");
        return app;
    }
    public String verify(LeaderboardProperties.App app,HttpServletRequest request,byte[] body) {
        String type=required(request,"x-msg-type",16);
        if(!"live_gift".equals(type)) throw RankException.badRequest("本入口只接收礼物消息");
        if(app.getMode()==LeaderboardProperties.Mode.SIGNED) {
            Map<String,String> headers=new TreeMap<>();
            headers.put("x-msg-type",type);headers.put("x-nonce-str",required(request,"x-nonce-str",128));
            headers.put("x-roomid",required(request,"x-roomid",64));headers.put("x-timestamp",required(request,"x-timestamp",32));
            if(app.getSecret()==null || app.getSecret().isEmpty()) throw new RankException(503,50303,"礼物验签未配置");
            long sent;
            try { sent=Long.parseLong(headers.get("x-timestamp")); }
            catch(NumberFormatException e) { throw denied(); }
            long now=clock.millis();
            if(sent<now-300000 || sent>now+30000) throw denied();
            byte[] provided;
            try { provided=Base64.getDecoder().decode(required(request,"x-signature",128)); }
            catch(IllegalArgumentException e) { throw denied(); }
            if(!MessageDigest.isEqual(signature(headers,body,app.getSecret()),provided)) throw denied();
            return headers.get("x-roomid");
        }
        if(!app.isPrivateIngressConfirmed() || app.getSource()==null || app.getSource().isEmpty()) throw new RankException(503,50303,"抖音云回调访问限制尚未确认");
        if(!app.getSource().equals(request.getHeader("x-tt-source"))) throw denied();
        String actual=request.getHeader("X-TT-AppID");
        if(actual!=null && !app.getAppId().equals(actual)) throw denied();
        String room=request.getHeader("x-roomid");
        if(room==null) room=request.getHeader("X-Room-ID");
        return ScoreRules.identifier(room,64,false);
    }
    public static byte[] signature(Map<String,String> headers,byte[] body,String secret) {
        try {
            StringBuilder canonical=new StringBuilder();
            for(Map.Entry<String,String> entry:new TreeMap<>(headers).entrySet()) {
                if(canonical.length()>0)canonical.append('&');canonical.append(entry.getKey()).append('=').append(entry.getValue());
            }
            // 算法由官方推送协议指定，直接使用原始 UTF-8 正文，不重新序列化。
            MessageDigest md=MessageDigest.getInstance("MD5");md.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
            md.update(body);md.update(secret.getBytes(StandardCharsets.UTF_8));return md.digest();
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String required(HttpServletRequest request,String key,int max) {
        String value=request.getHeader(key);
        if(value==null || value.isEmpty() || value.length()>max || value.indexOf('&')>=0 || !value.equals(value.trim())) throw denied();return value;
    }
    private RankException denied() { return new RankException(403,40301,"礼物来源验证失败"); }
}
