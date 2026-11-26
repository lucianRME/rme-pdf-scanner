package org.synapseworks.m35.workerprobe;
import android.content.*;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
/** Trusted host owns every file and observes isolated binder death before cleanup.
 * No broker for arbitrary paths/options; only fixed synthetic boundary operations.
 */
final class BoundaryExperiment implements ServiceConnection {
  final Coordinator host;final IBinder callback;final int mode;final File root;
  final CountDownLatch result=new CountDownLatch(1),entered=new CountDownLatch(1),boot=new CountDownLatch(1);
  volatile IBinder worker;volatile boolean bound,dead;volatile int uid,bits;volatile String bootstrap="NOT_RUN";
  ParcelFileDescriptor diagnostic;int traceOp,traceRole,traceErrno,traceCount;
  BoundaryExperiment(Coordinator h,IBinder c,int m)throws IOException{
    host=h;callback=c;mode=m;root=new File(h.getNoBackupFilesDir(),"boundary-"+UUID.randomUUID().toString().replace("-",""));
    if(!root.mkdirs())throw new IOException();
    for(String n:new String[]{"granted","coordinator","other-job/secret","previous-job/cache/secret"}){
      File f=new File(root,n);if(!f.getParentFile().isDirectory()&&!f.getParentFile().mkdirs())throw new IOException();
      byte[]expected=(n.equals("granted")?"GRANTED-SOURCE":"PRIVATE-SENTINEL").getBytes(java.nio.charset.StandardCharsets.UTF_8);
      try(FileOutputStream out=new FileOutputStream(f)){out.write(expected);}
      if(!Arrays.equals(expected,java.nio.file.Files.readAllBytes(f.toPath())))throw new IOException();
    }
    if(mode==3){extract("unpack",root,new long[]{0});File fonts=new File(root,"etc/fonts");if(!fonts.mkdirs())throw new IOException();File cache=new File(root,"cache");if(!cache.mkdirs())throw new IOException();
      try(FileOutputStream out=new FileOutputStream(new File(fonts,"fonts.conf"))){out.write(("<?xml version=\"1.0\"?><fontconfig><dir>"+new File(root,"user/fonts").getAbsolutePath()+"</dir><cachedir>"+cache.getAbsolutePath()+"</cachedir></fontconfig>").getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
  }
  void start(){bound=host.bindService(new Intent(host,IsolationService.class),this,Context.BIND_AUTO_CREATE);if(!bound)result.countDown();host.timer.execute(this::await);}
  public void onServiceConnected(ComponentName name,IBinder service){worker=service;host.io.execute(()->send(service));}
  public void onServiceDisconnected(ComponentName name){dead=true;result.countDown();boot.countDown();}
  void send(IBinder service){
    Parcel p=Parcel.obtain(),r=Parcel.obtain();
    try(ParcelFileDescriptor source=ParcelFileDescriptor.open(new File(root,"granted"),ParcelFileDescriptor.MODE_READ_ONLY);ParcelFileDescriptor output=ParcelFileDescriptor.open(new File(root,"result"),ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_READ_WRITE)){
      service.linkToDeath(()->{dead=true;result.countDown();boot.countDown();},0);
      p.writeInterfaceToken(IsolationService.API);p.writeInt(mode);
      p.writeStringArray(new String[]{"/data/user/0/"+Coordinator.CALLER+"/files/uid-sentinel",new File(root,"coordinator").getAbsolutePath(),new File(root,"other-job/secret").getAbsolutePath(),new File(root,"previous-job/cache/secret").getAbsolutePath()});
      p.writeString(root.getAbsolutePath());p.writeFileDescriptor(source.getFileDescriptor());p.writeFileDescriptor(output.getFileDescriptor());
      p.writeStrongBinder(new Binder(){protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){
        // Kernel-reported Binder UID, never a UID/PID supplied by native code.
        int calling=Binder.getCallingUid();if((code!=1&&code!=2)||calling==android.os.Process.myUid()||calling%100000<90000)return false;
        try{data.enforceInterface(IsolationService.API);if(uid!=0&&uid!=calling)return false;uid=calling;
          if(code==2){if(diagnostic!=null||reply==null)return false;ParcelFileDescriptor offered=data.readFileDescriptor();if(offered==null)return false;if(data.dataAvail()!=0||!Engine.sharedValid(offered.getFd(),4096)){Coordinator.close(offered);return false;}diagnostic=offered;reply.writeInt(1);return true;}
          String status=data.readString();int value=data.readInt();if(data.dataAvail()!=0)return false;
          if(status.equals("BOUNDARY_RESULT")){bits=value;result.countDown();}
          else if(status.equals("NATIVE_ENTERED"))entered.countDown();
          else {bootstrap=status;boot.countDown();}return true;
        }catch(Exception ignored){return false;}
      }});
      if(!service.transact(1,p,r,0)||r.readInt()!=1)throw new IOException();
    }catch(Exception ignored){result.countDown();}finally{p.recycle();r.recycle();}
  }
  void await(){String status="BOUNDARY_FAILED";boolean stopped=false,clean=false;long began=SystemClock.elapsedRealtime();
    try{
      boolean received=result.await(15,TimeUnit.SECONDS)&&uid!=0&&bits==4095;
      if(received&&mode==0)status="ISOLATED_BOUNDARY_PASS";
      if(received&&(mode==1||mode==2)&&entered.await(3,TimeUnit.SECONDS)){
        // A trusted deadline/cancel outside the hung process must kill even a
        // main-thread native hang. Unbind asks AMS to destroy its isolated UID.
        SystemClock.sleep(250);status="ISOLATED_HANG_TERMINATED";
      }
      if(received&&mode==3){boot.await(10,TimeUnit.SECONDS);status="ISOLATED_BOOTSTRAP_"+(dead?"DIED":bootstrap);}
    }catch(Exception ignored){}finally{
      if(bound){try{host.unbindService(this);}catch(Exception ignored){}bound=false;}
      long until=SystemClock.elapsedRealtime()+5000;while(worker!=null&&worker.isBinderAlive()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(20);
      stopped=worker!=null&&!worker.isBinderAlive();if(!stopped)status="ISOLATED_STOP_FAILED";
      try{if(stopped&&diagnostic!=null){byte[]data=Engine.readShared(diagnostic.getFd(),4096,0,4096);java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);int n=b.getInt(0);if(n>=0&&n<=63){traceCount=n;if(n>0){traceOp=b.getInt(20);traceRole=b.getInt(24);traceErrno=b.getInt(28);}}}}catch(Exception ignored){}Coordinator.close(diagnostic);
      try{if(stopped){byte[]source=java.nio.file.Files.readAllBytes(new File(root,"granted").toPath());if(!Arrays.equals(source,"GRANTED-SOURCE".getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new IOException();Coordinator.erase(root);clean=true;}}catch(Exception ignored){}
      host.boundary=null;
      Parcel p=Parcel.obtain();try{p.writeInterfaceToken("M35_BOUNDARY_RESULT_V1");p.writeString(status);p.writeInt(uid);p.writeInt(bits);p.writeInt(stopped?1:0);p.writeInt(clean?1:0);p.writeLong(SystemClock.elapsedRealtime()-began);p.writeInt(traceCount);p.writeInt(traceOp);p.writeInt(traceRole);p.writeInt(traceErrno);callback.transact(1,p,null,IBinder.FLAG_ONEWAY);}catch(Exception ignored){}finally{p.recycle();}
    }
  }
  void extract(String source,File target,long[]count)throws IOException{
    String[]children=host.getAssets().list(source);if(children==null)throw new IOException();
    if(children.length>0){if(!target.mkdirs()&&!target.isDirectory())throw new IOException();for(String name:children)extract(source+"/"+name,new File(target,name),count);}
    else try(InputStream in=host.getAssets().open(source);OutputStream out=new FileOutputStream(target)){byte[]b=new byte[16384];int n;while((n=in.read(b))!=-1){count[0]+=n;if(count[0]>64L*1024*1024)throw new IOException();out.write(b,0,n);}}
  }
}
