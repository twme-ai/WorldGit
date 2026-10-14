package org.worldgit.fabric.logic;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class VersionConfirmationTokensTest {
  @Test void tokenIsActorBoundSingleUseAndExpires() {
    var tokens=new VersionConfirmationTokens<String>();var operation=UUID.randomUUID();
    var code=tokens.issue(operation,"content","alice",1000,"retry");
    assertNull(tokens.consume(code,"bob",1100));
    var value=tokens.consume(code,"alice",1100);assertNotNull(value);assertEquals(operation,value.approval().operation());
    assertTrue(value.approval().permits("content","alice",1100));
    assertFalse(value.approval().permits("modified-world","alice",1100));
    assertFalse(value.approval().permits("content","bob",1100));
    assertFalse(value.approval().permits("content","alice",301000));
    assertNull(tokens.consume(code,"alice",1100));
    code=tokens.issue(operation,"content","alice",1000,"retry");assertNull(tokens.consume(code,"alice",301000));
    code=tokens.issue(operation,"content","alice",1000,"retry");tokens.clear();assertNull(tokens.consume(code,"alice",1100));
    assertNull(new VersionConfirmationTokens<String>().consume(code,"alice",1100));
  }
}
