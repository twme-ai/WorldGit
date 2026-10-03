package org.worldgit.hub;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.hub.collaboration.*;
class WebhookBoundaryTest {
  @Test void specialAddressesAreRejectedIncludingAlternateAndIpv6Encodings()throws Exception {
    for(String ip:List.of("127.0.0.1","127.1","0.0.0.0","10.1.2.3","172.16.0.1","192.168.1.1","169.254.169.254","100.64.0.1","198.18.0.1","224.0.0.1","::1","::","fc00::1","fe80::1","::ffff:127.0.0.1","2002:7f00:1::","2001:db8::1"))assertFalse(WebhookTarget.publicAddress(InetAddress.getByName(ip)),ip);
    assertTrue(WebhookTarget.publicAddress(InetAddress.getByName("8.8.8.8")));assertTrue(WebhookTarget.publicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    for(String url:List.of("https://127.0.0.1/private","https://2130706433/private","https://[::1]/private","http://8.8.8.8/","file:///etc/passwd","https://user:pass@8.8.8.8/","https://8.8.8.8/#fragment"))assertThrows(IllegalArgumentException.class,()->WebhookTarget.resolve(url,List.of()),url);
  }
  @Test void allowlistIsExactAndDnsIsPinned()throws Exception {
    var target=WebhookTarget.resolve("http://127.0.0.1:8099/hook",List.of("127.0.0.1"));assertArrayEquals(target.addresses(),target.resolver().resolve("127.0.0.1"));assertThrows(UnknownHostException.class,()->target.resolver().resolve("localhost"));
    assertThrows(IllegalArgumentException.class,()->WebhookTarget.resolve("http://127.0.0.1/",List.of("127.*")));
  }
  @Test void hmacHasKnownDigestAndChangesWithPayload(){assertEquals("sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",Webhooks.signature("key","The quick brown fox jumps over the lazy dog"));assertNotEquals(Webhooks.signature("key","one"),Webhooks.signature("key","two"));}
}
