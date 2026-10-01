package org.worldgit.fabric;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import org.worldgit.protocol.*;
import org.lwjgl.system.MemoryUtil;
import org.joml.Matrix4f;
import java.nio.*;
import java.util.*;

/** 共用幾何：模型只擷取一次；vertex/index buffer 上傳後整段預覽重用。 */
public final class GhostScene implements AutoCloseable {
    public record Quad(float[] xyzuv) {}
    public record Mesh(GpuBuffer buffer, int vertices, boolean textured, boolean pulse) {}
    private final List<Mesh> meshes=new ArrayList<>();
    private final Map<String,List<Quad>> models=new HashMap<>();
    private int ox,oy,oz;
    public int count;
    public long gpuBytes;
    private long lastFrame;
    private final double[] intervals=new double[240], submissions=new double[240];
    private int sample;
    private boolean measured=true;
    public void measure(){sample=0;lastFrame=0;measured=false;WorldGitClient.LOG.info("WGPOC MEASURE_START n={}",count);}
    public void build(List<Protocol.Entry> entries) {
        long started=System.nanoTime();count=entries.size();
        ox=entries.stream().mapToInt(Protocol.Entry::x).min().orElse(0);
        oy=entries.stream().mapToInt(Protocol.Entry::y).min().orElse(0);
        oz=entries.stream().mapToInt(Protocol.Entry::z).min().orElse(0);
        long[] lineVerts=new long[4];long ghostVerts=0;
        for(var e:entries) {
            if(e.type()!=DiffType.REMOVED)lineVerts[e.type().ordinal()]+=e.type()==DiffType.MODIFIED?48:24;
            if(e.type()==DiffType.REMOVED || e.type()==DiffType.MODIFIED) ghostVerts+=models.computeIfAbsent(e.otherState(),Adapter::model).size()*4L;
        }
        ByteBuffer[] lines=new ByteBuffer[4];
        for(int t=0;t<4;t++) if(lineVerts[t]>0)lines[t]=MemoryUtil.memAlloc(Math.toIntExact(lineVerts[t]*16)).order(ByteOrder.nativeOrder());
        ByteBuffer ghosts=ghostVerts>0?MemoryUtil.memAlloc(Math.toIntExact(ghostVerts*24)).order(ByteOrder.nativeOrder()):null;
        try {
            for(var e:entries) {
                float x=e.x()-ox,y=e.y()-oy,z=e.z()-oz;int rgb=e.type().rgb;
                if(e.type()!=DiffType.REMOVED)outline(lines[e.type().ordinal()],x,y,z,rgb,e.type()==DiffType.MODIFIED);
                if(e.type()==DiffType.REMOVED || e.type()==DiffType.MODIFIED) {
                    int alpha=e.type()==DiffType.REMOVED?108:58;
                    for(var q:models.get(e.otherState()))for(int v=0;v<4;v++) {
                        float[] a=q.xyzuv;int k=v*5;
                        ghosts.putFloat(x+(a[k]-.5f)*1.003f+.5f).putFloat(y+(a[k+1]-.5f)*1.003f+.5f).putFloat(z+(a[k+2]-.5f)*1.003f+.5f);
                        ghosts.putFloat(a[k+3]).putFloat(a[k+4]);color(ghosts,rgb,alpha);
                    }
                }
            }
            long cpuEnd=System.nanoTime();
            // 鬼影先畫；三種外框各自一個固定 buffer，衝突只改 uniform alpha。
            if(ghosts!=null)upload(ghosts,(int)ghostVerts,true,false);
            for(int t=0;t<4;t++)if(lines[t]!=null)upload(lines[t],(int)lineVerts[t],false,t==DiffType.CONFLICT.ordinal());
            long end=System.nanoTime();
            WorldGitClient.LOG.info("WGPOC BUFFER_READY n={} modelStates={} cpuBuildMs={} uploadMs={} totalBuildMs={} bytes={} vertices={}",count,models.size(),(cpuEnd-started)/1e6,(end-cpuEnd)/1e6,(end-started)/1e6,gpuBytes,meshes.stream().mapToInt(Mesh::vertices).sum());
        } finally {if(ghosts!=null)MemoryUtil.memFree(ghosts);for(var b:lines)if(b!=null)MemoryUtil.memFree(b);}
    }
    private void upload(ByteBuffer bytes,int vertices,boolean textured,boolean pulse) {
        bytes.flip();gpuBytes+=bytes.remaining();var b=RenderSystem.getDevice().createBuffer(()->"WorldGit static diff",GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_COPY_DST,bytes);
        meshes.add(new Mesh(b,vertices,textured,pulse));
    }
    private static void color(ByteBuffer b,int rgb,int a){b.put((byte)(rgb>>16)).put((byte)(rgb>>8)).put((byte)rgb).put((byte)a);}
    private static void vertex(ByteBuffer b,float x,float y,float z,int rgb){b.putFloat(x).putFloat(y).putFloat(z);color(b,rgb,255);}
    private static void segment(ByteBuffer b,float ax,float ay,float az,float bx,float by,float bz,int rgb){vertex(b,ax,ay,az,rgb);vertex(b,bx,by,bz,rgb);}
    private static void edge(ByteBuffer b,float ax,float ay,float az,float bx,float by,float bz,int rgb,boolean corner){
        if(!corner){segment(b,ax,ay,az,bx,by,bz,rgb);return;}
        float f=.23f;segment(b,ax,ay,az,ax+(bx-ax)*f,ay+(by-ay)*f,az+(bz-az)*f,rgb);
        segment(b,bx,by,bz,bx+(ax-bx)*f,by+(ay-by)*f,bz+(az-bz)*f,rgb);
    }
    private static void outline(ByteBuffer b,float x,float y,float z,int rgb,boolean corners){
        float lo=-.008f,hi=1.008f;
        for(int a=0;a<2;a++)for(int c=0;c<2;c++){
            float u=a==0?lo:hi,v=c==0?lo:hi;
            edge(b,x+lo,y+u,z+v,x+hi,y+u,z+v,rgb,corners);
            edge(b,x+u,y+lo,z+v,x+u,y+hi,z+v,rgb,corners);
            edge(b,x+u,y+v,z+lo,x+u,y+v,z+hi,rgb,corners);
        }
    }
    /** 只有相機平移與衝突透明度會逐幀更新。沒有方塊、palette 或模型遍歷。 */
    public void draw(Matrix4f view,double cx,double cy,double cz) {
        long begin=System.nanoTime();
        if(count==0)return;
        var transform=new Matrix4f(view).translate((float)(ox-cx),(float)(oy-cy),(float)(oz-cz));
        float pulse=(float)(.25+.75*(.5+.5*Math.sin(System.nanoTime()/1e9*Math.PI)));
        for(var mesh:meshes)Adapter.draw(mesh,transform,mesh.pulse?pulse:1f);
        long end=System.nanoTime();
        if(!measured && lastFrame!=0 && sample<intervals.length) {
            intervals[sample]=(begin-lastFrame)/1e6;submissions[sample]=(end-begin)/1e6;sample++;
        }
        lastFrame=begin;
        if(!measured && sample==intervals.length) {
            measured=true;double[] sorted=intervals.clone();Arrays.sort(sorted);
            WorldGitClient.LOG.info("WGPOC FRAME_METRIC n={} samples={} frameMeanMs={} frameP50Ms={} frameP95Ms={} submitMeanMs={} meshes={} (whole-frame interval; software-rendering reference)",count,sample,Arrays.stream(intervals).average().orElse(0),sorted[sample/2],sorted[(int)(sample*.95)],Arrays.stream(submissions).average().orElse(0),meshes.size());
        }
    }
    @Override public void close(){for(var m:meshes)m.buffer.close();meshes.clear();models.clear();count=0;}
}
