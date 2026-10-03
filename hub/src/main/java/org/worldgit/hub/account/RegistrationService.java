package org.worldgit.hub.account;

import java.time.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.worldgit.hub.config.CollaborationProperties;
import org.worldgit.hub.storage.NameRules;

@Service
public class RegistrationService {
  private final JdbcClient db; private final AccountService accounts; private final CollaborationProperties props;
  private final ObjectProvider<JavaMailSender> mail; private final AuthThrottle source;
  private final Map<String,long[]> rates=new HashMap<>();
  public RegistrationService(JdbcClient db,AccountService accounts,CollaborationProperties props,ObjectProvider<JavaMailSender> mail,AuthThrottle source) {this.db=db;this.accounts=accounts;this.props=props;this.mail=mail;this.source=source;}
  private synchronized void limit(HttpServletRequest req) {
    long now=System.currentTimeMillis();rates.entrySet().removeIf(e->now-e.getValue()[0]>=3600_000);
    String ip=source.sourceIp(req);
    if(!rates.containsKey(ip) && rates.size()>=10000) throw new AuthThrottle.Limited(3600);
    var r=rates.computeIfAbsent(ip,k->new long[]{now,0});
    if(++r[1]>3) throw new AuthThrottle.Limited((r[0]+3600_000-now+999)/1000);
  }
  @Transactional public void register(HttpServletRequest req,String username,String email,String password) {
    if(!props.registration().enabled()) throw new org.worldgit.hub.web.ApiError.NotFound("自助註冊已關閉");
    limit(req);
    if(!NameRules.validSlug(username) || password==null || password.length()<12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72 || email==null || email.length()>254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("帳號、信箱或密碼無效（密碼 12–72 字元）");
    var sender=mail.getIfAvailable();if(sender==null) throw new org.worldgit.hub.web.ApiError.Unavailable("尚未設定 SMTP");
    long now=System.currentTimeMillis();db.sql("DELETE FROM registrations WHERE expires_at<=?").param(now).update();
    email=email.toLowerCase(Locale.ROOT);
    // 對已存在的名稱／信箱也回相同訊息，防止列舉。
    if(accounts.findUser(username).isPresent() || db.sql("SELECT COUNT(*) FROM account_emails WHERE email=?").param(email).query(Long.class).single()>0 || db.sql("SELECT COUNT(*) FROM registrations WHERE username=? OR email=?").params(username,email).query(Long.class).single()>0) return;
    if(db.sql("SELECT COUNT(*) FROM registrations").query(Long.class).single()>=10000) throw new org.worldgit.hub.web.ApiError.Unavailable("註冊稍後再試");
    String token=AccountService.randomToken(32);
    db.sql("INSERT INTO registrations(id,username,email,password_hash,token_hash,expires_at) VALUES (?,?,?,?,?,?)")
      .params(UUID.randomUUID().toString(),username,email,new BCryptPasswordEncoder(10).encode(password),AccountService.sha256(token),now+3600_000).update();
    var message=new SimpleMailMessage();message.setFrom(props.registration().from());message.setTo(email);message.setSubject("WorldGit 信箱驗證");
    message.setText("一小時內開啟驗證頁："+props.registration().publicUrl()+"/verify#"+token);sender.send(message);
  }
  @Transactional public void verify(String token) {
    if(!props.registration().enabled()) throw new org.worldgit.hub.web.ApiError.NotFound("自助註冊已關閉");
    if(token==null || token.length()>128) throw new IllegalArgumentException("驗證碼無效");
    var row=db.sql("SELECT id,username,email,password_hash FROM registrations WHERE token_hash=? AND expires_at>?")
      .params(AccountService.sha256(token),System.currentTimeMillis()).query((rs,n)->List.of(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4))).optional().orElseThrow(()->new IllegalArgumentException("驗證碼無效或過期"));
    // 唯一 token 刪除是並行兌換的 CAS。
    if(db.sql("DELETE FROM registrations WHERE id=?").param(row.get(0)).update()!=1) throw new IllegalArgumentException("驗證碼已使用");
    var u=accounts.createUser(row.get(1),AccountService.randomToken(32),false);
    db.sql("UPDATE users SET password_hash=? WHERE id=?").params(row.get(3),u.id()).update();
    db.sql("INSERT INTO account_emails(user_id,email) VALUES (?,?)").params(u.id(),row.get(2)).update();
  }
}
