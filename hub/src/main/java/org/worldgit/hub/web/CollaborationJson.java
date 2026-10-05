package org.worldgit.hub.web;

import java.io.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.worldgit.hub.operation.Outcomes;

/** 全部 JSON 終態、4 MiB 上限及 private,no-store；binary／SSE 保留原 converter。 */
@RestControllerAdvice(basePackages="org.worldgit.hub.web")
public class CollaborationJson implements ResponseBodyAdvice<Object> {
  private final ObjectMapper json;
  public CollaborationJson(ObjectMapper json){this.json=json;}
  @Override public boolean supports(MethodParameter method,Class<? extends HttpMessageConverter<?>> converter){return org.springframework.http.converter.json.MappingJackson2HttpMessageConverter.class.isAssignableFrom(converter);}
  @Override public Object beforeBodyWrite(Object body,MethodParameter method,MediaType type,Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest req,ServerHttpResponse res){
    if(body==null || body instanceof org.springframework.web.servlet.mvc.method.annotation.SseEmitter)return body;
    try {
      var request=Outcomes.request();
      if(request!=null && (!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod()) || request.getAttribute("worldgit.result")!=null)) {
        var shape=json.valueToTree(body);var dimension=shape.path("dimension").asText("");if(dimension.isEmpty())dimension=shape.path("pr").path("dimension").asText("");
        if(!dimension.isEmpty())request.setAttribute("worldgit.dimension",dimension);
        var result=Outcomes.result(request);if(res instanceof ServletServerHttpResponse servlet)Outcomes.headers(servlet.getServletResponse(),json,result);
        if(!(body instanceof Collection<?>) && !body.getClass().isArray()) {
          var node=json.valueToTree(body);if(node.isObject())((com.fasterxml.jackson.databind.node.ObjectNode)node).set("result",json.valueToTree(result));body=node;
        }
      }
      byte[] bytes=BoundedJson.encode(json,body);res.getHeaders().setContentType(MediaType.APPLICATION_JSON);res.getHeaders().setCacheControl("private, no-store");res.getHeaders().setContentLength(bytes.length);res.getBody().write(bytes);return null;
    }catch(org.worldgit.core.normalize.DecodeBudget.Exceeded e){throw new ApiError.JsonBudget(e.getMessage());}catch(IOException e){throw new UncheckedIOException(e);}
  }
}
