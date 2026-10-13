package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;

public class RankRequestDeserializer extends JsonDeserializer<RankRequest> {
    @Override public RankRequest deserialize(JsonParser parser,DeserializationContext context)throws IOException {
        JsonNode node=parser.getCodec().readTree(parser);
        if(!node.isObject())throw JsonMappingException.from(parser,"请求必须为对象");
        RankRequest request=new RankRequest();JsonNode limit=node.get("limit");
        if(limit!=null&&!limit.isNull()) {
            if(!limit.isIntegralNumber()||!limit.canConvertToInt())throw JsonMappingException.from(parser,"limit必须为整数");
            request.setLimit(limit.intValue());
        }
        request.setGame(string(node,"game",parser));request.setPlatform(string(node,"platform",parser));
        request.setFaction(string(node,"faction",parser));request.setZhubo(string(node,"zhubo",parser));request.setOrderBy(string(node,"order_by",parser));
        return request;
    }
    private String string(JsonNode node,String field,JsonParser parser)throws IOException {JsonNode value=node.get(field);if(value==null||value.isNull())return null;if(!value.isTextual())throw JsonMappingException.from(parser,field+"必须为字符串");return value.textValue();}
}
