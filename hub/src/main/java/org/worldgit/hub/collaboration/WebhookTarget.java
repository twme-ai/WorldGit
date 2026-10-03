package org.worldgit.hub.collaboration;
import java.net.*;
import java.util.*;
import org.apache.hc.client5.http.DnsResolver;

/** 每次投遞解析且驗證全部 IP，再把固定地址交給 socket；不受二次 DNS/rebinding 影響。 */
public final class WebhookTarget {
  private WebhookTarget() {}
  public record Target(URI uri,InetAddress[] addresses) {
    public DnsResolver resolver(){String expected=uri.getHost().replace("[", "").replace("]", "");return new DnsResolver(){
      public InetAddress[] resolve(String host)throws UnknownHostException {if(!host.replace("[", "").replace("]", "").equalsIgnoreCase(expected))throw new UnknownHostException("unexpected host");return addresses.clone();}
      public String resolveCanonicalHostname(String host)throws UnknownHostException {if(!host.replace("[", "").replace("]", "").equalsIgnoreCase(expected))throw new UnknownHostException("unexpected host");return expected;}
    };}
  }
  public static Target resolve(String value,List<String> allowed)throws UnknownHostException {
    URI u;try{u=URI.create(value);}catch(RuntimeException ex){throw new IllegalArgumentException("webhook URL 無效");}
    if(value.length()>2048 || u.getHost()==null || u.getUserInfo()!=null || u.getFragment()!=null || !Set.of("http","https").contains(u.getScheme()) || u.getPort()==0 || u.getPort()>65535)throw new IllegalArgumentException("webhook URL 無效");
    String host=u.getHost();if(host.startsWith("["))host=host.substring(1,host.length()-1);final String normalized=host;
    boolean exception=allowed.stream().anyMatch(h->h.equalsIgnoreCase(normalized));
    if(!exception && !u.getScheme().equals("https"))throw new IllegalArgumentException("公開 webhook 必須 HTTPS");
    InetAddress[] addresses=InetAddress.getAllByName(host);if(addresses.length==0)throw new UnknownHostException();
    for(var ip:addresses)if(!exception && !publicAddress(ip))throw new IllegalArgumentException("webhook 不允許內網、loopback、保留位址");
    return new Target(u,addresses);
  }
  public static boolean publicAddress(InetAddress ip){
    if(ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress() || ip.isSiteLocalAddress() || ip.isMulticastAddress())return false;
    byte[] b=ip.getAddress();int a=b[0]&255;
    if(b.length==4){int c=b[1]&255;return a!=0 && a!=10 && a!=127 && a<224 && !(a==100 && c>=64 && c<=127) && !(a==169 && c==254) && !(a==172 && c>=16 && c<=31) && !(a==192 && (c==168 || c==0 || c==2)) && !(a==198 && (c==18 || c==19 || c==51)) && !(a==203 && c==0);}
    // IPv6 僅 global unicast；排除 2001:: 特殊用途／隧道與文件網段、6to4（可封裝內網 IPv4）。
    return (a&0xe0)==0x20 && !(a==0x20 && (b[1]&255)==1 && ((b[2]&254)==0 || (b[2]&255)==0x0d && (b[3]&255)==0xb8)) && !(a==0x20 && (b[1]&255)==2);
  }
}
