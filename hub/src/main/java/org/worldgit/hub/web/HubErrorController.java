package org.worldgit.hub.web;

import java.util.Map;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.*;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.web.bind.annotation.*;
import org.worldgit.hub.operation.Reports;

/** Servlet／未匹配路由的錯誤也使用同一報告；不公開路由、stack 或例外內容。 */
@RestController
public class HubErrorController implements ErrorController {
  private final Reports reports;
  public HubErrorController(Reports reports){this.reports=reports;}
  @RequestMapping("/error")
  Map<String,Object> error(HttpServletRequest request,HttpServletResponse response){
    Object status=request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    int code=status instanceof Number number?number.intValue():500;
    response.setStatus(code);
    String message=switch(code){case 400->"請求格式無效";case 401->"需要有效憑證";case 403->"權限不足";case 404->"找不到頁面或 API";case 405->"不支援這個 HTTP 方法";case 413->"請求超過大小上限";default->"HTTP "+code+"：請求失敗";};
    return reports.error(request,"http-"+code,message);
  }
}
