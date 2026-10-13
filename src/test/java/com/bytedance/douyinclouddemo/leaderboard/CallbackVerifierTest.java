package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CallbackVerifierTest {
    private final CallbackVerifier verifier=new CallbackVerifier(new LeaderboardProperties(),new Fixtures.MutableClock());
    static Map<String,String> headers(long now){Map<String,String> h=new TreeMap<>();h.put("x-msg-type","live_gift");h.put("x-nonce-str","nonce");h.put("x-roomid","room1");h.put("x-timestamp",Long.toString(now));return h;}
    static MockHttpServletRequest request(LeaderboardProperties.App app,byte[] body,long now){MockHttpServletRequest r=new MockHttpServletRequest();Map<String,String> h=headers(now);h.forEach(r::addHeader);r.addHeader("x-signature",Base64.getEncoder().encodeToString(CallbackVerifier.signature(h,body,app.getSecret())));return r;}
    @Test void matchesOfficialUtf8SignatureVector(){Map<String,String> h=headers(456789);h.put("x-nonce-str","123456");h.put("x-roomid","268");assertEquals("PDcKhdlsrKEJif6uMKD2dw==",Base64.getEncoder().encodeToString(CallbackVerifier.signature(h,"abc123你好".getBytes(StandardCharsets.UTF_8),"123abc")));}
    @Test void verifiesRawBytesAndRejectsBodyTampering(){LeaderboardProperties.App app=Fixtures.app();byte[] body="[ {\"value\":1} ]".getBytes(StandardCharsets.UTF_8);MockHttpServletRequest r=request(app,body,Fixtures.NOW);assertEquals("room1",verifier.verify(app,r,body));assertEquals(403,assertThrows(RankException.class,()->verifier.verify(app,r,"[]".getBytes(StandardCharsets.UTF_8))).getHttpStatus());}
    @Test void expiredSignatureIsRejected(){LeaderboardProperties.App app=Fixtures.app();byte[] body="[]".getBytes(StandardCharsets.UTF_8);assertThrows(RankException.class,()->verifier.verify(app,request(app,body,Fixtures.NOW-300001),body));}
    @Test void cloudSourceHeaderAloneCannotEnableTrust(){LeaderboardProperties.App app=Fixtures.app();app.setMode(LeaderboardProperties.Mode.CLOUD);app.setSource("configured-source");MockHttpServletRequest r=new MockHttpServletRequest();r.addHeader("x-msg-type","live_gift");r.addHeader("x-tt-source","configured-source");r.addHeader("X-Room-ID","room1");assertEquals(503,assertThrows(RankException.class,()->verifier.verify(app,r,new byte[0])).getHttpStatus());app.setPrivateIngressConfirmed(true);assertEquals("room1",verifier.verify(app,r,new byte[0]));}
    @Test void disabledBindingIsNotAnOpenIngestionEndpoint(){assertEquals(503,assertThrows(RankException.class,()->verifier.app("main")).getHttpStatus());}
}
