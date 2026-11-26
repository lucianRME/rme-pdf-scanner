package org.synapseworks.m35.workerprobe;
import android.content.*;
import android.os.*;
import android.system.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Trusted finite capability owner. No worker-supplied path is ever opened here. */
final class IsolatedJob {
  static final long WRITABLE=CapabilityProfile.WRITABLE;
  final Coordinator host;final Coordinator.Job job;
  final ArrayList<ParcelFileDescriptor> resources=new ArrayList<>(),scratch=new ArrayList<>();
  final ArrayList<String> names=new ArrayList<>();
  ParcelFileDescriptor output,trace;Session session;String[]guards;File validated;volatile long reserved;
  IsolatedJob(Coordinator h,Coordinator.Job j){host=h;job=j;}
  static ParcelFileDescriptor sealed(int size,boolean terminal)throws IOException{int fd=Engine.sealed(size,terminal);if(fd<0)throw new IOException();return ParcelFileDescriptor.adoptFd(fd);}
  void prepare()throws Exception{
    File assets=new File(job.root,"resources");extract("unpack",assets,new long[]{0});
    File config=new File(assets,"etc/fonts/fonts.conf");if(!config.getParentFile().mkdirs())throw new IOException();
    try(FileOutputStream out=new FileOutputStream(config)){out.write(CapabilityProfile.FONT_CONFIG.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    ArrayList<File> files=new ArrayList<>();collect(assets,files);files.sort(Comparator.comparing(File::getAbsolutePath));
    long size=new File(job.root,"input."+job.kind).length();
    for(File f:files){host.guard(job);if(!f.setReadOnly())throw new IOException();size+=f.length();if(size>CapabilityProfile.READ_ONLY)throw new IOException();names.add("/m35/job/"+assets.toPath().relativize(f.toPath()).toString());resources.add(ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY));}
    names.add("/m35/job/input."+job.kind);resources.add(ParcelFileDescriptor.open(new File(job.root,"input."+job.kind),ParcelFileDescriptor.MODE_READ_ONLY));
    if(resources.size()!=CapabilityProfile.RESOURCES)throw new IOException();
    job.monitor=host.timer.scheduleAtFixedRate(()->host.monitor(job),0,100,java.util.concurrent.TimeUnit.MILLISECONDS);
    File control=new File(host.getNoBackupFilesDir(),"guard-fixtures");
    String[]paths={"coordinator/secret","sibling-job/secret","previous-job/cache/secret"};
    for(String name:paths){File f=new File(control,name);if(!f.getParentFile().isDirectory()&&!f.getParentFile().mkdirs())throw new IOException();byte[]expected="SYNTHETIC-PRIVATE-CONTROL".getBytes(java.nio.charset.StandardCharsets.US_ASCII);if(!f.isFile())try(FileOutputStream out=new FileOutputStream(f)){out.write(expected);}if(!Arrays.equals(Files.readAllBytes(f.toPath()),expected))throw new IOException();}
    guards=new String[]{"/data/user/0/"+Coordinator.CALLER+"/files/uid-sentinel",new File(control,paths[0]).getAbsolutePath(),new File(control,paths[1]).getAbsolutePath(),new File(control,paths[2]).getAbsolutePath()};
    session=new Session(false);session.bind();
  }
  static void collect(File dir,List<File>files)throws IOException{File[]children=dir.listFiles();if(children==null)throw new IOException();for(File f:children){if(Files.isSymbolicLink(f.toPath()))throw new IOException();if(f.isDirectory())collect(f,files);else files.add(f);if(files.size()>32)throw new IOException();}}
  void extract(String source,File target,long[]count)throws IOException{
    String[]children=host.getAssets().list(source);if(children==null)throw new IOException();
    if(children.length>0){if(!target.mkdirs()&&!target.isDirectory())throw new IOException();for(String name:children){if(name.contains("/")||name.equals(".."))throw new IOException();extract(source+"/"+name,new File(target,name),count);}}
    else try(InputStream in=host.getAssets().open(source);OutputStream out=new FileOutputStream(target)){byte[]b=new byte[16384];int n;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();count[0]+=n;if(count[0]>CapabilityProfile.UNPACKED)throw new IOException();out.write(b,0,n);}}
  }
  final class Session implements ServiceConnection {
    final boolean validation;volatile IBinder binder;volatile boolean bound,stopping,completed;int uid,pid;
    Session(boolean v){validation=v;}
    void bind(){bound=host.bindService(new Intent(host,NativeService.class),this,Context.BIND_AUTO_CREATE);if(!bound)host.finish(job,"START_FAILED");}
    public void onServiceConnected(ComponentName name,IBinder b){if(stopping||job.finished.get())return;binder=b;host.io.execute(()->start(b));}
    public void onServiceDisconnected(ComponentName name){if(!stopping&&!completed)host.io.execute(()->host.finish(job,"ENGINE_DIED"));}
    void start(IBinder b){
      if(job.finished.get()||job.cancelled.get()){host.finish(job,job.cancellation);return;}
      Parcel p=Parcel.obtain(),reply=Parcel.obtain();
      try{
        host.guard(job);probeProtocol(b,false);
        b.linkToDeath(()->{if(!stopping&&!completed)host.io.execute(()->host.finish(job,"ENGINE_DIED"));},0);
        p.writeInterfaceToken(NativeService.DESCRIPTOR);p.writeString(job.nonce);p.writeString(validation?"pdf":job.kind);p.writeInt(validation?0:job.testMode);p.writeStrongBinder(new Binder(){protected boolean onTransact(int code,Parcel data,Parcel r,int flags){
          if(stopping||completed||job.finished.get()||data.dataSize()>(code==4?4096:512))return false;
          int actualUid=Binder.getCallingUid(),actualPid=Binder.getCallingPid();if(actualUid==android.os.Process.myUid()||actualUid%100000<90000)return false;
          try{
            data.enforceInterface(NativeService.RESULT);if(!job.nonce.equals(data.readString())){if(code==1)job.callbackBits|=1;if(code==3)job.callbackBits|=2;return false;}
            if(code==0){if(uid!=0||actualPid<=1||data.dataAvail()!=0||r==null)return false;uid=actualUid;pid=actualPid;job.pid=pid;identity(validation?"validator.pid":"engine.pid",pid,uid);if(validation){job.validatorUid=uid;job.validatorPid=pid;}else{job.engineUid=uid;job.enginePid=pid;}r.writeInt(1);return true;}
            if(uid!=actualUid)return false;
            if(code==4){
              if(validation||output!=null||!scratch.isEmpty()||r==null)return false;if(data.readInt()!=29)throw new IOException();
              ArrayList<ParcelFileDescriptor> offered=new ArrayList<>();try{
                for(int i=0;i<31;i++){ParcelFileDescriptor cap=data.readFileDescriptor();if(cap==null)throw new IOException();offered.add(cap);int size=i<29?CapabilityProfile.scratchSize(i):i==29?32*1024*1024:4096;if(!Engine.sharedValid(cap.getFd(),size))throw new IOException();}
                if(data.dataAvail()!=0)throw new IOException();int[]ids=new int[31];for(int i=0;i<31;i++)ids[i]=offered.get(i).getFd();if(!Engine.independent(ids))throw new IOException();scratch.addAll(offered.subList(0,29));output=offered.get(29);trace=offered.get(30);offered.clear();reserved=WRITABLE;r.writeInt(1);return true;
              }finally{for(ParcelFileDescriptor cap:offered)Coordinator.close(cap);}
            }
            if(code==2){data.readInt();if(data.dataAvail()!=0)return false;Parcel event=Parcel.obtain();try{event.writeInterfaceToken("M35_ENTERED_V1");event.writeString(job.nonce);job.callback.transact(2,event,null,IBinder.FLAG_ONEWAY);}finally{event.recycle();}return true;}
            if(code==6){data.readInt();if(validation||data.dataAvail()!=0)return false;Parcel event=Parcel.obtain();try{event.writeInterfaceToken("M35_MAIN_ENTERED_V1");event.writeString(job.nonce);job.callback.transact(3,event,null,IBinder.FLAG_ONEWAY);}finally{event.recycle();}return true;}
            if(code==3){int bits=data.readInt();if(validation||job.guardBits!=0||data.dataAvail()!=0)return false;job.guardBits=bits;return true;}
            if(code==5){int bits=data.readInt();if(validation||job.vfsBits!=0||data.dataAvail()!=0)return false;job.vfsBits=bits;return true;}
            if(code==7){int values=data.readInt();if(validation||data.dataAvail()!=0)return false;job.guardErrnos=values;return true;}
            if(code!=1)return false;String status=data.readString();if(status==null||!Arrays.asList("OK","ENGINE_FAILED","VFS_SETUP_FAILED","VFS_CONTROL_FAILED","SANDBOX_FAILED","GUARD_FAILED","INIT_FAILED","CONVERT_FAILED","OUTPUT_LIMIT").contains(status))return false;
            long bytes=data.readLong();int pages=data.readInt();long init=data.readLong(),conversion=data.readLong(),heap=data.readLong(),nativeHeap=data.readLong(),pss=data.readLong();
            if(data.dataAvail()!=0||bytes<0||bytes>32L*1024*1024||pages<0||pages>10||init<0||init>60000||conversion<0||conversion>60000||heap<0||heap>96L*1024*1024||nativeHeap<0||nativeHeap>512L*1024*1024||pss<0||pss>512L*1024*1024)return false;
            completed=true;host.io.execute(()->result(status,bytes,pages,init,conversion,heap,nativeHeap,pss));return true;
          }catch(Exception ignored){host.requestCancel(job,code==4?"CAPABILITY_REFUSED":"IPC_FAILED");return false;}
        }});
        if(!b.transact(0,p,reply,0)||reply.readInt()!=1||uid==0)throw new IOException();host.guard(job);probeProtocol(b,true);p.recycle();reply.recycle();p=Parcel.obtain();reply=Parcel.obtain();
        p.writeInterfaceToken(NativeService.DESCRIPTOR);p.writeString(job.nonce);p.writeString(validation?"pdf":job.kind);p.writeInt(validation?0:job.testMode);
        p.writeStringArray(validation?new String[]{"/m35/validation.pdf"}:names.toArray(new String[0]));
        if(validation){p.writeInt(1);try(ParcelFileDescriptor source=ParcelFileDescriptor.open(validated,ParcelFileDescriptor.MODE_READ_ONLY)){p.writeFileDescriptor(source.getFileDescriptor());p.writeInt(0);p.writeStringArray(new String[0]);if(!b.transact(1,p,reply,0)||reply.readInt()!=1)throw new IOException();}}
        else {p.writeInt(resources.size());for(ParcelFileDescriptor cap:resources)p.writeFileDescriptor(cap.getFileDescriptor());p.writeInt(0);p.writeStringArray(guards);if(!b.transact(1,p,reply,0)||reply.readInt()!=1)throw new IOException();}
        Parcel replay=Parcel.obtain(),rejected=Parcel.obtain();try{replay.writeInterfaceToken(NativeService.DESCRIPTOR);if(b.transact(1,replay,rejected,0))throw new IOException();job.protocolBits|=256;}finally{replay.recycle();rejected.recycle();}
      }catch(Exception ignored){host.finish(job,job.cancelled.get()?job.cancellation:"START_FAILED");}finally{p.recycle();reply.recycle();}
    }
    void probeProtocol(IBinder b,boolean afterHello)throws Exception{
      int first=afterHello?4:0,last=afterHello?8:4;String kind=validation?"pdf":job.kind;
      for(int test=first;test<last;test++){
        host.guard(job);Parcel p=Parcel.obtain(),r=Parcel.obtain();try{
          p.writeInterfaceToken(NativeService.DESCRIPTOR);int code=test==0?99:test==1?1:test<5?0:1;
          if(test==2||test==3||test==4){p.writeString(test==2?"invalid":job.nonce);p.writeString(test==3?"invalid":kind);p.writeInt(0);p.writeStrongBinder(new Binder());}
          else if(test==5||test==6){p.writeString(test==5?(job.nonce.charAt(0)=='0'?"1":"0")+job.nonce.substring(1):job.nonce);p.writeString(kind);p.writeInt(0);if(test==6)p.writeInt(0);}
          else if(test==7)p.writeByteArray(new byte[16384]);
          boolean handled=b.transact(code,p,r,0);boolean wantReply=test>=2&&test<=6;
          if(wantReply?(!handled||r.readInt()!=0):handled)throw new IOException();job.protocolBits|=1<<test;
        }finally{p.recycle();r.recycle();}
      }
    }
    boolean stop(){
      stopping=true;if(bound){try{host.unbindService(this);}catch(Exception ignored){}bound=false;}
      long end=SystemClock.elapsedRealtime()+5000;while(binder!=null&&binder.isBinderAlive()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(20);
      boolean dead=binder==null||!binder.isBinderAlive();if(dead&&binder!=null){job.workerDeaths++;binder=null;}return dead;
    }
    void result(String status,long bytes,int pages,long init,long conversion,long heap,long nativeHeap,long pss){
      if(job.finished.get())return;
      job.javaPeak=Math.max(job.javaPeak,heap);job.nativePeak=Math.max(job.nativePeak,nativeHeap);job.pssPeak=Math.max(job.pssPeak,pss);
      if(!validation){job.initMs=init;job.convertMs=conversion;}
      if(!stop()){host.finish(job,"STOP_FAILED");return;}
      if(!status.equals("OK")){host.finish(job,status);return;}
      try{
        host.guard(job);
        if(validation){if(pages<1||pages>10)throw new IOException();job.pages=pages;host.publish(job,"OK");return;}
        if(job.guardBits!=131071||job.protocolBits!=511||job.vfsBits!=2047||job.callbackBits!=3||bytes<1||bytes>32L*1024*1024)throw new IOException();job.outputProtection=Engine.freezeOutput(output.getFd());if(job.outputProtection==0)throw new IOException();
        for(ParcelFileDescriptor p:scratch)Coordinator.close(p);scratch.clear();for(ParcelFileDescriptor p:resources)Coordinator.close(p);resources.clear();
        reserved=32L*1024*1024+4096;
        validated=new File(job.root,"result.pdf");MessageDigest digest=MessageDigest.getInstance("SHA-256");
        byte[]buffer=new byte[65536];long position=0;try(FileOutputStream out=new FileOutputStream(validated)){while(position<bytes){host.guard(job);byte[]part=Engine.readShared(output.getFd(),32*1024*1024,(int)position,(int)Math.min(buffer.length,bytes-position));if(part==null||part.length==0)throw new IOException();int count=part.length;System.arraycopy(part,0,buffer,0,count);out.write(buffer,0,count);digest.update(buffer,0,count);position+=count;}out.getFD().sync();}
        byte[]header=new byte[5],tail=new byte[(int)Math.min(1024,bytes)];try(RandomAccessFile in=new RandomAccessFile(validated,"r")){in.readFully(header);in.seek(bytes-tail.length);in.readFully(tail);}
        if(!Arrays.equals(header,"%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII))||!new String(tail,java.nio.charset.StandardCharsets.ISO_8859_1).trim().endsWith("%%EOF")||!validated.setReadOnly())throw new IOException();
        job.quarantineHash=digest.digest();session=new Session(true);session.bind();
      }catch(Exception ignored){host.finish(job,job.cancelled.get()?job.cancellation:"QUARANTINE_FAILED");}
    }
  }
  void identity(String name,int pid,int uid)throws IOException{try(FileOutputStream out=new FileOutputStream(new File(job.root,name));DataOutputStream data=new DataOutputStream(out)){data.writeInt(0x35354644);data.writeInt(pid);data.writeInt(uid);data.flush();out.getFD().sync();}}
  boolean stop(){boolean dead=session==null||session.stop();if(dead){for(ParcelFileDescriptor p:resources)Coordinator.close(p);resources.clear();for(ParcelFileDescriptor p:scratch)Coordinator.close(p);scratch.clear();recordTrace();Coordinator.close(output);output=null;Coordinator.close(trace);trace=null;reserved=0;}return dead;}
  void recordTrace(){if(trace==null)return;try{if(Engine.freezeOutput(trace.getFd())==0)return;byte[]data=Engine.readShared(trace.getFd(),4096,0,4096);ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);int count=b.getInt(0);if(count<0||count>63)return;job.traceCount=count;StringBuilder codes=new StringBuilder();for(int i=1;i<=count;i++){int op=b.getInt(16*i+4),role=b.getInt(16*i+8),err=b.getInt(16*i+12);if(op<0||op>64||role<0||role>32||err<0||err>256)return;if(i>1)codes.append(";");codes.append(op).append(":").append(role).append(":").append(err);}job.traceCodes=codes.toString();if(count>0){int op=b.getInt(20),role=b.getInt(24),err=b.getInt(28);if(op>=0&&op<=64&&role>=0&&role<=32&&err>=0&&err<=256){job.traceOp=op;job.traceRole=role;job.traceErrno=err;}}}catch(Exception ignored){}}
  static void recover(File jobs)throws Exception{
    File[]children=jobs.listFiles();if(children==null)return;
    for(File job:children)for(String name:new String[]{"engine.pid","validator.pid"}){
      File f=new File(job,name);if(!f.isFile())continue;if(f.length()!=12)throw new IOException();int pid,uid;
      try(DataInputStream in=new DataInputStream(new FileInputStream(f))){if(in.readInt()!=0x35354644)throw new IOException();pid=in.readInt();uid=in.readInt();}
      if(pid<=1||pid==android.os.Process.myPid()||uid%100000<90000)throw new IOException();
      long end=SystemClock.elapsedRealtime()+5000;boolean dead=false;
      while(SystemClock.elapsedRealtime()<end){try{Os.kill(pid,0);}catch(ErrnoException e){if(e.errno==OsConstants.ESRCH){dead=true;break;}if(e.errno!=OsConstants.EPERM)throw e;}SystemClock.sleep(20);}
      if(!dead)throw new IOException();
    }
  }
}
