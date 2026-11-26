package org.synapseworks.m35.workerprobe;
import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.graphics.pdf.PdfRenderer;
import android.graphics.Bitmap;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.libreoffice.kit.LibreOfficeKit;
/** One isolated UID per engine OR independent PDF validation job. No path broker. */
public final class NativeService extends Service {
  static final String DESCRIPTOR="M35_CAPABILITY_WORKER_V1",RESULT="M35_CAPABILITY_RESULT_V1";
  final AtomicBoolean hello=new AtomicBoolean(),used=new AtomicBoolean();
  String nonce,expectedKind;IBinder callback;final ArrayList<ParcelFileDescriptor> local=new ArrayList<>();final ExecutorService executor=Executors.newSingleThreadExecutor();
  public IBinder onBind(Intent intent){return new Binder(){protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){
    if(code==INTERFACE_TRANSACTION){reply.writeString(DESCRIPTOR);return true;}
    if(Binder.getCallingUid()!=getApplicationInfo().uid||data.dataSize()>16384||reply==null)return false;
    ArrayList<ParcelFileDescriptor> owned=new ArrayList<>();
    try{
      data.enforceInterface(DESCRIPTOR);
      if(code==0){String token=data.readString(),kind=data.readString();int fault=data.readInt();IBinder cb=data.readStrongBinder();if(token==null||!token.matches("[a-f0-9]{32}")||!Arrays.asList("docx","xlsx","pptx","pdf").contains(kind)||fault<0||fault>7||cb==null||data.dataAvail()!=0||!hello.compareAndSet(false,true)){reply.writeInt(0);return true;}
        nonce=token;expectedKind=kind;callback=cb;Parcel p=Parcel.obtain(),r=Parcel.obtain();try{p.writeInterfaceToken(RESULT);p.writeString(nonce);if(!callback.transact(0,p,r,0)||r.readInt()!=1)throw new IOException();}finally{p.recycle();r.recycle();}
        for(int stale:new int[]{1,3}){p=Parcel.obtain();r=Parcel.obtain();try{p.writeInterfaceToken(RESULT);p.writeString((nonce.charAt(0)=='0'?"1":"0")+nonce.substring(1));if(callback.transact(stale,p,r,0))throw new IOException();}finally{p.recycle();r.recycle();}}
        if(!kind.equals("pdf")){
          for(int i=0;i<29;i++)local.add(ParcelFileDescriptor.adoptFd(Engine.shared(i==0&&fault==4?1024*1024+4096:CapabilityProfile.scratchSize(i))));local.add(ParcelFileDescriptor.adoptFd(Engine.shared(32*1024*1024)));local.add(ParcelFileDescriptor.adoptFd(Engine.shared(4096)));
          if(fault==5&&Engine.freezeOutput(local.get(0).getFd())==0)throw new IOException();
          p=Parcel.obtain();r=Parcel.obtain();try{p.writeInterfaceToken(RESULT);p.writeString(nonce);p.writeInt(fault==6?30:29);for(int i=0;i<local.size();i++)p.writeFileDescriptor(local.get(fault==7&&i==1?0:i).getFileDescriptor());if(!callback.transact(4,p,r,0)||r.readInt()!=1)throw new IOException();}finally{p.recycle();r.recycle();}
        }reply.writeInt(1);return true;}
      if(code!=1||!hello.get()||used.get())return false;
      String token=data.readString(),kind=data.readString();int mode=data.readInt();
      if(!nonce.equals(token)||!expectedKind.equals(kind)||mode<0||mode>3)throw new IOException();
      int named=data.readInt();if(named<1||named>48)throw new IOException();String[]names=new String[named];for(int i=0;i<named;i++){names[i]=data.readString();if(names[i]==null||names[i].length()>767)throw new IOException();}
      int count=data.readInt();if(count!=names.length)throw new IOException();
      for(int i=0;i<count;i++){ParcelFileDescriptor p=data.readFileDescriptor();if(p==null)throw new IOException();owned.add(p);}
      int pools=data.readInt();if(pools!=0)throw new IOException();
      int guardCount=data.readInt();if(guardCount!=(kind.equals("pdf")?0:4))throw new IOException();String[]guards=new String[guardCount];for(int i=0;i<guardCount;i++){guards[i]=data.readString();if(guards[i]==null||guards[i].length()>767)throw new IOException();}
      if(data.dataAvail()!=0||!used.compareAndSet(false,true))throw new IOException();
      if(kind.equals("pdf")&&(count!=1||pools!=0||mode!=0))throw new IOException();
      ArrayList<ParcelFileDescriptor> caps=new ArrayList<>(owned);caps.addAll(local);local.clear();owned.clear();executor.execute(()->run(kind,mode,names,count,kind.equals("pdf")?0:29,caps,guards));reply.writeInt(1);return true;
    }catch(Exception ignored){for(ParcelFileDescriptor p:owned)Coordinator.close(p);reply.writeInt(0);return true;}
  }};}
  void run(String kind,int mode,String[]names,int count,int pools,ArrayList<ParcelFileDescriptor> caps,String[]guards){
    String status="ENGINE_FAILED";long init=0,convert=0,size=0;int pages=0;
    AtomicBoolean running=new AtomicBoolean(true);AtomicLong heap=new AtomicLong(),nativeHeap=new AtomicLong(),pss=new AtomicLong();
    Thread monitor=new Thread(()->{while(running.get()){heap.accumulateAndGet(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(),Math::max);nativeHeap.accumulateAndGet(Debug.getNativeHeapAllocatedSize(),Math::max);pss.accumulateAndGet(Debug.getPss()*1024L,Math::max);SystemClock.sleep(100);}},"m35-sample");monitor.start();
    try{
      if(!Engine.quiet())throw new IOException();
      if(kind.equals("pdf")){
        android.system.StructStat source=android.system.Os.fstat(caps.get(0).getFileDescriptor());
        if(!android.system.OsConstants.S_ISREG(source.st_mode)||source.st_size<=0||source.st_size>32L*1024*1024||(android.system.Os.fcntlInt(caps.get(0).getFileDescriptor(),android.system.OsConstants.F_GETFL,0)&android.system.OsConstants.O_ACCMODE)!=android.system.OsConstants.O_RDONLY)throw new IOException();
        Class.forName("android.graphics.pdf.PdfRenderer");
        if(!Engine.sandbox()){status="SANDBOX_FAILED";throw new IOException();}
        try(PdfRenderer renderer=new PdfRenderer(ParcelFileDescriptor.dup(caps.get(0).getFileDescriptor()))){
          pages=renderer.getPageCount();if(pages<1||pages>10)throw new IOException();
          for(int i=0;i<pages;i++)try(PdfRenderer.Page page=renderer.openPage(i)){
            if(page.getWidth()<1||page.getHeight()<1||page.getWidth()>14400||page.getHeight()>14400)throw new IOException();
            double scale=Math.min(1.0,1024.0/Math.max(page.getWidth(),page.getHeight()));
            Bitmap bitmap=Bitmap.createBitmap(Math.max(1,(int)(page.getWidth()*scale)),Math.max(1,(int)(page.getHeight()*scale)),Bitmap.Config.ARGB_8888);
            try{page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);}finally{bitmap.recycle();}
          }
        }status="OK";
      }else{
        long began=SystemClock.elapsedRealtime();LibreOfficeKit.loadLibraries();int[]fds=new int[count],scratch=new int[pools];for(int i=0;i<count;i++)fds[i]=caps.get(i).getFd();for(int i=0;i<pools;i++)scratch[i]=caps.get(count+i).getFd();
        if(!LibreOfficeKit.configureCapabilities(names,fds,scratch,caps.get(count+pools).getFd(),caps.get(count+pools+1).getFd(),getApplicationInfo().sourceDir)){status="VFS_SETUP_FAILED";throw new IOException();}
        int controls=LibreOfficeKit.capabilityControls();event(5,controls);if(controls!=2047){status="VFS_CONTROL_FAILED";throw new IOException();}
        // Initialize only pinned engine/resources/fonts before locking backing
        // creation. ART allocates JNI reference tables for bootstrap threads.
        // No documentLoad call or untrusted Office parsing occurs before TSYNC.
        if(!LibreOfficeKit.initialize(this,new File("/m35/job"),new File("/m35/job/cache"))){status="INIT_FAILED";throw new IOException();}
        int source=-1;for(int i=0;i<count;i++)if(names[i].equals("/m35/job/input."+kind))source=i;if(source<0)throw new IOException();
        // The adapter already owns its duplicates. Release transfer originals
        // before installing the fixed FD ceiling: newer ART starts with more
        // descriptors. Keep only the three capabilities needed by raw probes.
        try(ParcelFileDescriptor read=ParcelFileDescriptor.dup(caps.get(source).getFileDescriptor());ParcelFileDescriptor small=ParcelFileDescriptor.dup(caps.get(count).getFileDescriptor());ParcelFileDescriptor write=ParcelFileDescriptor.dup(caps.get(count+pools).getFileDescriptor())){
          for(ParcelFileDescriptor p:caps)Coordinator.close(p);caps.clear();
          if(!Engine.sandbox()){status="SANDBOX_FAILED";throw new IOException();}
          long observed=Engine.engineGuards(guards,read.getFd(),small.getFd(),write.getFd());int bits=(int)observed;event(7,(int)(observed>>>32));
          event(3,bits);if(bits!=131071){status="GUARD_FAILED";throw new IOException();}
        }
        init=SystemClock.elapsedRealtime()-began;
        Engine.entered=()->{if(mode==3)new Handler(getMainLooper()).post(()->{event(6,0);Engine.hang();});event(2,0);};
        long start=SystemClock.elapsedRealtime();int code=Engine.convertGated(LibreOfficeKit.getLibreOfficeKitHandle(),"file:///m35/job/input."+kind,"file:///m35/job/result.pdf",mode==1||mode==3);convert=SystemClock.elapsedRealtime()-start;
        if(code!=0){status="CONVERT_FAILED";throw new IOException();}
        size=LibreOfficeKit.capabilityStats()[0];status=size>0&&size<=32L*1024*1024?"OK":"OUTPUT_LIMIT";
      }
    }catch(Throwable ignored){}finally{running.set(false);try{monitor.join(1000);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}for(ParcelFileDescriptor p:caps)Coordinator.close(p);Engine.entered=null;}
    Parcel p=Parcel.obtain();try{p.writeInterfaceToken(RESULT);p.writeString(nonce);p.writeString(status);p.writeLong(size);p.writeInt(pages);p.writeLong(init);p.writeLong(convert);p.writeLong(heap.get());p.writeLong(nativeHeap.get());p.writeLong(pss.get());callback.transact(1,p,null,IBinder.FLAG_ONEWAY);}catch(Exception ignored){}finally{p.recycle();}
  }
  void event(int code,int value){Parcel p=Parcel.obtain();try{p.writeInterfaceToken(RESULT);p.writeString(nonce);p.writeInt(value);callback.transact(code,p,null,IBinder.FLAG_ONEWAY);}catch(Exception ignored){}finally{p.recycle();}}
  public void onDestroy(){executor.shutdownNow();for(ParcelFileDescriptor cap:local)Coordinator.close(cap);local.clear();super.onDestroy();}
}
