package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.mojang.brigadier.CommandDispatcher;
import org.junit.jupiter.api.Test;
import org.worldgit.core.capture.PlayerTouchedEntities;
import org.worldgit.core.operation.OperationResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

class Phase5CommandTest {
  @Test void targetFlagsSurroundTypedArgumentsAndLeaveGreedyTextIntact() throws Exception {
    var requests=new ArrayList<CommandRequest>();
    var dispatcher=new CommandDispatcher<io.papermc.paper.command.brigadier.CommandSourceStack>();
    var suggestions=new CommandSuggestions(k->CompletableFuture.completedFuture(List.of()),()->null);
    dispatcher.register(new CommandTree((source,request)->requests.add(request),suggestions,CommandTreeTest.NativeArgument::new).build());
    var source=CommandTreeTest.source(true,Set.of("worldgit.admin"));
    for(var command:List.of("init --world world --dimension minecraft:the_nether --template creative",
        "status --dimension minecraft:the_nether --full","restore HEAD --dimension minecraft:the_nether --dry-run",
        "switch --dimension minecraft:the_nether main","branch topic --dimension minecraft:the_nether",
        "remote --dimension minecraft:the_nether list","push origin main --all","commit --dimension minecraft:the_nether -m title --all",
        "ignore --dimension minecraft:the_nether add entity minecraft:cow","ignore move 3 1","ignore confirm abcd1234",
        "tag example --dimension minecraft:overworld","verify HEAD","log --graph --all","log --graph --page 2","diff HEAD~1 --radius 2")) {
      assertDoesNotThrow(()->dispatcher.execute("wg "+command,source),command);
    }
    dispatcher.execute("wg commit --dimension minecraft:the_nether -m title --all",source);
    var request=requests.getLast();assertEquals("title --all",request.text("text"));assertFalse(request.flag("--all"));
  }
  @Test void copiedErrorIsIndependentOfExistingClickAndContainsMaskedReport() {
    var original=Component.text("old").clickEvent(ClickEvent.suggestCommand("/wg reset --hard"));
    var report=OperationResult.ErrorReport.create("FAIL",UUID.randomUUID(),"fetch",null,"v","1.21.11","Folia","token=secret",List.of("secret"));
    var result=OperationUi.copy(original,report);
    assertEquals(original.clickEvent(),result.clickEvent());
    var copy=result.children().getLast();assertEquals(ClickEvent.Action.COPY_TO_CLIPBOARD,copy.clickEvent().action());
    assertFalse(copy.clickEvent().value().contains("secret"));assertNotNull(copy.hoverEvent());
  }
  @Test void closureCarriesEveryUuidAndRemainsWithinCoreSidecarContract() {
    var ids=new TreeSet<UUID>(List.of(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()));
    var result=new TreeSet<UUID>();PlayerTouchedEntities.collect(TouchedEntities.nbt(ids),result);assertEquals(ids,result);
    assertEquals(ids,assertDoesNotThrow(()->PlayerTouchedEntities.parse(PlayerTouchedEntities.bytes(result))));
  }
  @Test void targetFlagsHaveBoundedPacketTreeWithoutSharedMutation() {
    var suggestions=new CommandSuggestions(k->CompletableFuture.completedFuture(List.of()),()->null);
    var tree=new CommandTree((source,request)->{},suggestions,CommandTreeTest.NativeArgument::new).build().build();
    assertTrue(nodes(tree)<20000,"target decorations must not recursively decorate themselves");
  }
  private static int nodes(com.mojang.brigadier.tree.CommandNode<?> node) {return 1+node.getChildren().stream().mapToInt(Phase5CommandTest::nodes).sum();}
}
