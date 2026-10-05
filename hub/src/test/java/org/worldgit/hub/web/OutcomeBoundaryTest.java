package org.worldgit.hub.web;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import org.worldgit.core.operation.OperationResult;
class OutcomeBoundaryTest {
  public Object action(){return Map.of();}
  @Test void oversizeJsonFailsBeforeWritingBody()throws Exception {
    var req=new MockHttpServletRequest("GET","/api/v1/graph");var res=new MockHttpServletResponse();RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req,res));
    try{var advice=new CollaborationJson(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
      assertThrows(ApiError.JsonBudget.class,()->advice.beforeBodyWrite(Map.of("data","x".repeat(4<<20)),new MethodParameter(getClass().getMethod("action"),-1),MediaType.APPLICATION_JSON,MappingJackson2HttpMessageConverter.class,new ServletServerHttpRequest(req),new ServletServerHttpResponse(res)));assertEquals(0,res.getContentAsByteArray().length);
    }finally{RequestContextHolder.resetRequestAttributes();}
  }
  @Test void jsonActionsHaveAllResultStatusesButResourcesKeepTheirConverter()throws Exception {
    var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();var advice=new CollaborationJson(json);
    var method=new MethodParameter(getClass().getMethod("action"),-1);
    assertTrue(advice.supports(method,MappingJackson2HttpMessageConverter.class));
    assertFalse(advice.supports(method,ResourceHttpMessageConverter.class));assertFalse(advice.supports(method,ByteArrayHttpMessageConverter.class));assertFalse(advice.supports(method,StringHttpMessageConverter.class));
    for(var status:OperationResult.Status.values())for(boolean array:List.of(true,false)) {
      var req=new MockHttpServletRequest("POST","/api/v1/action");var res=new MockHttpServletResponse();
      var result=new OperationResult(UUID.randomUUID(),"action",status,null,Map.of("message","完成狀態"),1,List.of(),null);req.setAttribute("worldgit.result",result);
      RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req,res));
      try {advice.beforeBodyWrite(array?List.of(Map.of("ok",true)):Map.of("ok",true),method,MediaType.APPLICATION_JSON,MappingJackson2HttpMessageConverter.class,new ServletServerHttpRequest(req),new ServletServerHttpResponse(res));
        var header=json.readTree(Base64.getDecoder().decode(res.getHeader("X-WorldGit-Result")));assertEquals(status.name(),header.path("status").asText());assertEquals(result.operationId().toString(),header.path("operationId").asText());
        var body=json.readTree(res.getContentAsByteArray());if(array)assertTrue(body.isArray());else assertEquals(status.name(),body.path("result").path("status").asText());
      }finally{RequestContextHolder.resetRequestAttributes();}
    }
  }
}
