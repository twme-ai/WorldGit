package org.worldgit.hub.web;
import java.io.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
/** Phase 4 聚合 JSON 在輸出前限制 4 MiB；私人授權回應一律不快取。 */
@RestControllerAdvice(assignableTypes={PullRequestController.class,CommentController.class,ReleaseController.class,WebhookController.class,NotificationController.class,PermissionController.class,BranchPolicyController.class,OAuthController.class})
public class CollaborationJson implements ResponseBodyAdvice<Object> {
  private final ObjectMapper json;
  public CollaborationJson(ObjectMapper json){this.json=json;}
  @Override public boolean supports(MethodParameter method,Class<? extends HttpMessageConverter<?>> converter){return true;}
  @Override public Object beforeBodyWrite(Object body,MethodParameter method,MediaType type,Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest req,ServerHttpResponse res){
    if(body==null)return null;
    try{byte[] bytes=BoundedJson.encode(json,body);res.getHeaders().setContentType(MediaType.APPLICATION_JSON);res.getHeaders().setCacheControl("private, no-store");res.getHeaders().setContentLength(bytes.length);res.getBody().write(bytes);return null;}catch(IOException e){throw new UncheckedIOException(e);}
  }
}
