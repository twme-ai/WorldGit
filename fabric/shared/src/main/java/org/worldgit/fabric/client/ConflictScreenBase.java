package org.worldgit.fabric.client;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.worldgit.core.merge.MergeReport.Choice;
import org.worldgit.fabric.logic.ClientConflicts;

/** 可鍵盤操作的分頁清單。畫面不暫停整合伺服器；預覽及寫入使用不同的按鈕。 */
abstract class ConflictScreenBase extends Screen {
  private int page;
  private long generation=-1;
  ConflictScreenBase() { super(t("title")); }
  static Component t(String key, Object... args) { return Component.translatable("worldgit.conflicts."+key,args); }
  @Override public boolean isPauseScreen() { return false; }
  @Override public void tick() {
    var current=ClientRuntime.get().conflicts().generation();
    if(current!=generation) rebuildWidgets();
  }
  private void label(int x,int y,int width,Component text,int rows) {
    addRenderableOnly(new MultiLineTextWidget(x,y,text,font).setMaxWidth(width).setMaxRows(rows));
  }
  private Button button(int x,int y,int w,Component text,Runnable action,boolean enabled) {
    var b=addRenderableWidget(Button.builder(text,ignored -> action.run()).bounds(x,y,w,20).build()); b.active=enabled; return b;
  }
  @Override protected void init() {
    var rt=ClientRuntime.get(); var model=rt.conflicts(); generation=model.generation();
    var regions=model.regions(); int left=Math.min(185,Math.max(112,width/3)), right=left+20, rw=width-right-12;
    int controls=height-76, rows=Math.max(1,(controls-58)/22), pages=Math.max(1,(regions.size()+rows-1)/rows);
    page=Math.min(page,pages-1);
    label(12,8,width-100,t("title"),1);
    button(width-84,5,72,t("close"),this::onClose,true);
    label(12,30,width-24,t(rt.mergeCapable() ? "summary" : "unsupported",regions.stream().filter(r->!r.info().resolved()).count(),regions.size()),1);
    for(int i=page*rows;i<Math.min(regions.size(),(page+1)*rows);i++) {
      var r=regions.get(i); boolean selected=r.key().equals(model.selected());
      var text=t("row",selected ? ">" : r.info().resolved() ? "✓" : "!",r.info().id(),r.info().blockCount());
      var b=button(12,50+(i-page*rows)*22,left,text,()->{rt.selectConflict(r.key());rebuildWidgets();},true);
      b.setTooltip(Tooltip.create(Component.literal(r.key().dimension().value()+"\n"+r.info().bounds())));
    }
    if(regions.isEmpty()) label(12,55,left,t("empty"),3);
    if(pages>1) {
      button(12,controls-24,32,Component.literal("<"),()->{page--;rebuildWidgets();},page>0);
      label(49,controls-20,left-74,Component.literal((page+1)+" / "+pages),1);
      button(left-20,controls-24,32,Component.literal(">"),()->{page++;rebuildWidgets();},page+1<pages);
    }
    var selected=model.selected()==null ? null : model.region(model.selected());
    boolean active=selected!=null && rt.mergeCapable();
    if(selected!=null) {
      var r=selected.info(); var b=r.bounds();
      label(right,50,rw,Component.literal("#"+r.id()+" · "+selected.key().dimension().value()),1);
      label(right,65,rw,b==null ? t("metadata") : Component.literal("("+b.minX()+", "+b.minY()+", "+b.minZ()+") → ("+b.maxX()+", "+b.maxY()+", "+b.maxZ()+")"),2);
      label(right,88,rw,t("authors",String.join(", ",r.oursAuthors()),String.join(", ",r.theirsAuthors())),2);
      label(right,112,rw,t("state",t(r.resolved() ? "resolved" : "pending"),r.choice().name().toLowerCase(Locale.ROOT)),1);
      label(right,127,rw,t("warnings",t(r.redstone() ? "redstone" : "no_redstone"),selected.hints().size()),2);
      if(!selected.hints().isEmpty()) {
        var info=button(right,controls-24,Math.max(65,rw/2-3),t("hints"),()->{},true);
        info.setTooltip(Tooltip.create(Component.literal(selected.hints().stream().limit(20).map(Object::toString).reduce((a,c)->a+"\n"+c).orElse(""))));
      }
      button(right+rw/2,controls-24,rw-rw/2,t("goto"),rt::gotoConflict,active && b!=null);
    } else label(right,60,rw,t("select"),3);
    int group=width-24, labelWidth=Math.min(80,width/5), each=(group-labelWidth)/3;
    label(12,controls+6,labelWidth,t("preview"),1);
    for(int i=0;i<3;i++) {
      var choice=Choice.values()[i];
      button(12+labelWidth+i*each,controls,each-2,t(choice.name().toLowerCase(Locale.ROOT)),()->{rt.previewConflict(choice);onClose();},active);
    }
    label(12,controls+30,labelWidth,t("apply"),1);
    for(int i=0;i<3;i++) {
      var choice=Choice.values()[i];
      button(12+labelWidth+i*each,controls+24,each-2,t(choice.name().toLowerCase(Locale.ROOT)),()->rt.applyConflict(choice,false),active && rt.singleplayer());
    }
    int quarter=group/4;
    for(int i=0;i<4;i++) {
      var choice=Choice.values()[i];
      button(12+i*quarter,controls+48,quarter-2,t("resolve",t(choice.name().toLowerCase(Locale.ROOT))),()->rt.applyConflict(choice,true),active);
    }
  }
}
