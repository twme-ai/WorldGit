package org.worldgit.hub.web;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.worldgit.hub.collaboration.EventService;
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
  private final Access access;private final EventService events;
  public NotificationController(Access access,EventService events){this.access=access;this.events=events;}
  @GetMapping Object list(HttpServletRequest req,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit){return events.notifications(access.requireUser(req),offset,limit);}
  @PutMapping("/{id}/seen") Object seen(HttpServletRequest req,@PathVariable String id){access.scope(req,"write");events.seen(access.requireUser(req),id);return Map.of("ok",true);}
}
