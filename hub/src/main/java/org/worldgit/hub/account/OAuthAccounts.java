package org.worldgit.hub.account;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.worldgit.hub.account.Models.User;
import org.worldgit.hub.config.CollaborationProperties;

@Service
public class OAuthAccounts {
  private final JdbcClient db; private final AccountService accounts; private final CollaborationProperties props;
  public OAuthAccounts(JdbcClient db,AccountService accounts,CollaborationProperties props) { this.db=db; this.accounts=accounts; this.props=props; }
  @Transactional public User login(String provider,String subject,User link) {
    if(subject==null || subject.isBlank() || subject.length()>256) throw new IllegalArgumentException("OAuth subject 無效");
    var existing=db.sql("SELECT user_id FROM oauth_identities WHERE provider=? AND subject=?").params(provider,subject).query(String.class).optional();
    if(existing.isPresent()) {
      if(link!=null && !existing.get().equals(link.id())) throw new SecurityException("此第三方帳號已連結其他帳號");
      return accounts.userById(existing.get()).orElseThrow();
    }
    User u=link;
    if(u==null) throw new SecurityException("先完成本機帳號註冊與信箱驗證，再連結 OAuth");
    db.sql("INSERT INTO oauth_identities(provider,subject,user_id,created_at) VALUES (?,?,?,?)").params(provider,subject,u.id(),System.currentTimeMillis()).update();
    return u;
  }
  public List<Map<String,Object>> list(User user) {
    return db.sql("SELECT provider,created_at FROM oauth_identities WHERE user_id=? ORDER BY provider").param(user.id()).query((rs,n)->Map.<String,Object>of("provider",rs.getString(1),"createdAt",rs.getLong(2))).list();
  }
  @Transactional public void unlink(User u,String provider) {
    long only=db.sql("SELECT COUNT(*) FROM oauth_only_users WHERE user_id=?").param(u.id()).query(Long.class).single();
    if(only>0 && list(u).size()<=1) throw new IllegalArgumentException("不能解除最後一種登入方式；先設定本機密碼");
    db.sql("DELETE FROM oauth_identities WHERE user_id=? AND provider=?").params(u.id(),provider).update();
  }
  @Transactional public void password(User u,String password) {
    if(password==null || password.length()<12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72) throw new IllegalArgumentException("密碼需 12–72 字元");
    db.sql("UPDATE users SET password_hash=? WHERE id=?").params(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(10).encode(password),u.id()).update();
    db.sql("DELETE FROM oauth_only_users WHERE user_id=?").param(u.id()).update();
  }
}
