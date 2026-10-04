package org.worldgit.protocol;

import java.io.*;
import java.util.*;
import org.worldgit.core.model.DimensionId;

/** 可選、有界的留言快照；id floor 使 hide 後的晚到分片不能重建顯示。 */
public final class CommentsProtocol {
  private CommentsProtocol() {}
  public static final String CAPABILITY="comments-v1", CHANNEL="worldgit:comments";
  public static final int MAX_COMMENTS=64, MAX_PARTS=8, MAX_BATCH=131072;
  public record Comment(String id,String author,String text,int x,int y,int z,Integer maxX,Integer maxY,Integer maxZ) {
    public Comment {
      UUID.fromString(id);
      if(author.codePointCount(0,author.length())>33 || text.codePointCount(0,text.length())>241
          || author.length()>128 || text.length()>964 || Math.abs((long)x)>30000000 || Math.abs((long)z)>30000000
          || y < -2048 || y > 2048 || (maxX==null)!=(maxY==null) || (maxX==null)!=(maxZ==null)
          || maxX!=null && (maxX<x || maxY<y || maxZ<z || maxX>30000000 || maxZ>30000000 || maxY>2048))
        throw new IllegalArgumentException("invalid comment");
    }
  }
  public record Part(long id,DimensionId dimension,int sequence,int parts,int total,List<Comment> comments) {}
  public record Snapshot(long id,DimensionId dimension,List<Comment> comments) {}
  public static List<byte[]> encode(long id,DimensionId dimension,List<Comment> comments) throws IOException {
    if(id<0 || comments.size()>MAX_COMMENTS)throw new IOException("comment limit");
    var groups=new ArrayList<List<Comment>>();var current=new ArrayList<Comment>();int size=0;
    for(var c:comments) {
      var b=new ByteArrayOutputStream();write(new DataOutputStream(b),c);
      if(size+b.size()>26000) {groups.add(List.copyOf(current));current.clear();size=0;}
      current.add(c);size+=b.size();
    }
    groups.add(List.copyOf(current));var out=new ArrayList<byte[]>();int total=0;
    for(int i=0;i<groups.size();i++) {
      var bytes=new ByteArrayOutputStream();var data=new DataOutputStream(bytes);
      data.writeByte(1);data.writeByte(2);data.writeLong(id);data.writeUTF(dimension.value());
      data.writeByte(i);data.writeByte(groups.size());data.writeByte(comments.size());data.writeByte(groups.get(i).size());
      for(var c:groups.get(i))write(data,c);
      if(bytes.size()>Protocol.MAX_PAYLOAD || (total+=bytes.size())>MAX_BATCH || groups.size()>MAX_PARTS)throw new IOException("comment budget");
      out.add(bytes.toByteArray());
    }
    return List.copyOf(out);
  }
  private static void write(DataOutputStream d,Comment c) throws IOException {
    d.writeUTF(c.id());d.writeUTF(c.author());d.writeUTF(c.text());d.writeInt(c.x());d.writeInt(c.y());d.writeInt(c.z());
    d.writeBoolean(c.maxX()!=null);if(c.maxX()!=null) {d.writeInt(c.maxX());d.writeInt(c.maxY());d.writeInt(c.maxZ());}
  }
  public static Part decode(byte[] bytes) throws IOException {
    if(bytes.length>Protocol.MAX_PAYLOAD)throw new IOException("comment payload limit");
    try {
      var d=new DataInputStream(new ByteArrayInputStream(bytes));
      if(d.readUnsignedByte()!=1 || d.readUnsignedByte()!=2)throw new IOException("comment version/type");
      long id=d.readLong();var dim=new DimensionId(d.readUTF());int seq=d.readUnsignedByte(),parts=d.readUnsignedByte(),total=d.readUnsignedByte(),count=d.readUnsignedByte();
      if(id<0 || parts<1 || parts>MAX_PARTS || seq>=parts || total>MAX_COMMENTS || count>total || total==0 && parts!=1 || total>0 && (parts>total || count==0))throw new IOException("comment envelope limit");
      var rows=new ArrayList<Comment>();
      for(int i=0;i<count;i++) {
        String key=d.readUTF(),author=d.readUTF(),text=d.readUTF();int x=d.readInt(),y=d.readInt(),z=d.readInt();boolean range=d.readBoolean();
        rows.add(new Comment(key,author,text,x,y,z,range?d.readInt():null,range?d.readInt():null,range?d.readInt():null));
      }
      if(d.available()!=0)throw new IOException("trailing comment bytes");
      return new Part(id,dim,seq,parts,total,List.copyOf(rows));
    } catch(IllegalArgumentException e) {throw new IOException("invalid comment",e);}
  }
  public static final class Assembler {
    private long floor=-1,started;
    private Part first;
    private final Map<Integer,List<Comment>> pieces=new HashMap<>();
    private int bytes;
    public Optional<Snapshot> accept(byte[] packet,long now) throws IOException {
      var p=decode(packet);if(p.id()<=floor)return Optional.empty();
      if(first!=null && now-started>5000) {floor=Math.max(floor,first.id());first=null;pieces.clear();bytes=0;}
      if(p.id()<=floor || first!=null && p.id()<first.id())return Optional.empty();
      if(first==null || p.id()>first.id()) {first=p;pieces.clear();bytes=0;started=now;}
      if(!first.dimension().equals(p.dimension()) || first.total()!=p.total() || first.parts()!=p.parts())throw new IOException("comment envelope mismatch");
      if(pieces.containsKey(p.sequence()))throw new IOException("duplicate comment part");
      if((bytes+=packet.length)>MAX_BATCH)throw new IOException("comment batch budget");
      if(pieces.values().stream().mapToInt(List::size).sum()+p.comments().size()>p.total())throw new IOException("comment count limit");
      pieces.put(p.sequence(),p.comments());
      if(pieces.size()!=p.parts())return Optional.empty();
      var rows=new ArrayList<Comment>();for(int i=0;i<p.parts();i++)rows.addAll(pieces.get(i));
      if(rows.size()!=p.total() || rows.stream().map(Comment::id).distinct().count()!=rows.size())throw new IOException("comment count/ids");
      floor=p.id();first=null;pieces.clear();bytes=0;
      return Optional.of(new Snapshot(p.id(),p.dimension(),List.copyOf(rows)));
    }
    public void clear() {if(first!=null)floor=Math.max(floor,first.id());first=null;pieces.clear();bytes=0;}
    public void reset() {clear();floor=-1;}
  }
}
