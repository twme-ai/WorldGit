package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;

class RemoteConfigTest {
  @Test void jgitMissingCredentialProviderIsAnAuthenticationFailure() {
    assertTrue(RemoteCommands.authenticationRequired("http://127.0.0.1:8096/admin/castle: Authentication is required but no CredentialsProvider has been registered"));
    assertTrue(RemoteCommands.authenticationRequired("HTTP 401 Unauthorized"));
    assertFalse(RemoteCommands.authenticationRequired("Connection refused"));
    assertFalse(RemoteCommands.authenticationRequired("http://host/world401/forbidden: Connection refused"));
    assertFalse(RemoteCommands.hasStatus("http://host:404/alice/world401: Connection refused",401));
    for(int code:List.of(401,403,404,409,429))assertTrue(RemoteCommands.hasStatus("http://host/alice/world: "+code+" rejected",code));
  }
  @Test void conflictChunksCountEvenWhenTheOursPlanIsEmpty() {
    var cell=new org.worldgit.core.merge.MergeReport.Cell(4,224,0);
    var atom=new org.worldgit.core.merge.MergeReport.Atom(org.worldgit.core.merge.MergeReport.Kind.BLOCK,cell,"",List.of(),null);
    var metadata=new org.worldgit.core.merge.MergeReport.Atom(org.worldgit.core.merge.MergeReport.Kind.METADATA,new org.worldgit.core.merge.MergeReport.Cell(640,0,640),"",List.of(),null);
    var region=new org.worldgit.core.merge.MergeReport.Region(1,org.worldgit.core.model.DimensionId.OVERWORLD,new org.worldgit.core.apply.BlockBox(4,224,0,4,224,0),1,List.of(),List.of(),false,org.worldgit.core.merge.MergeReport.Choice.OURS,false,List.of(atom,atom,metadata));
    var report=new org.worldgit.core.merge.MergeReport(0,List.of(region),List.of(),List.of(),List.of());
    assertEquals(1,RemoteCommands.affectedChunks(Map.of(),Map.of(org.worldgit.core.model.DimensionId.OVERWORLD,report)));
  }
  @Test void configTypesSecretsDefaultsAndPermissions() throws Exception {
    var c=new YamlConfiguration();assertFalse(RemoteConfig.from(c).webhook().enabled());assertEquals(0,RemoteConfig.from(c).fetchIntervalSeconds());
    c.loadFromString("remote:\n  token: secret-pat\n");var error=assertThrows(IllegalArgumentException.class,()->RemoteConfig.from(c));assertFalse(error.getMessage().contains("secret-pat"));
    var invalid=new YamlConfiguration();invalid.set("remote.timeout-seconds","30");assertThrows(IllegalArgumentException.class,()->RemoteConfig.from(invalid));
    var scalar=new YamlConfiguration();scalar.set("remote.webhook",true);assertThrows(IllegalArgumentException.class,()->RemoteConfig.from(scalar));
    var resource=new YamlConfiguration();resource.loadFromString(new String(getClass().getResourceAsStream("/plugin.yml").readAllBytes()));
    for(String command:List.of("remote","fetch","push","pull","pr","comment")) {assertEquals("op",resource.getString("permissions.worldgit.command."+command+".default"));assertTrue(resource.getBoolean("permissions.worldgit.admin.children.worldgit.command."+command));}
    assertEquals("comment",CommandTree.permission("comments"));assertEquals("pull",CommandTree.permission("pull"));
  }
  @Test void lateRestResultsCannotUndoHideDimensionChangeOrLogout() {
    var requests=new DisplayRequests();var a=UUID.randomUUID();var b=UUID.randomUUID();
    long first=requests.reserve(a),other=requests.reserve(b);requests.cancel(a);
    assertFalse(requests.current(a,first));assertTrue(requests.current(b,other));
    long next=requests.reserve(a);assertFalse(requests.current(a,first));assertTrue(requests.current(a,next));
    requests.clear();assertFalse(requests.current(a,next));assertFalse(requests.current(b,other));
  }
  @Test void captureExcludesOnlyWorldGitDisplayMarkers() {
    var display=new Nbt.Compound();display.put("id","minecraft:text_display");display.put("Tags",new Nbt.ListTag((byte)8,List.of("worldgit_comment")));
    assertTrue(TransientDisplays.excluded(display));display.put("Tags",new Nbt.ListTag((byte)8,List.of("user_marker")));assertFalse(TransientDisplays.excluded(display));
    display.put("id","minecraft:cow");display.put("Tags",new Nbt.ListTag((byte)8,List.of("worldgit_comment")));assertFalse(TransientDisplays.excluded(display));
  }
}
