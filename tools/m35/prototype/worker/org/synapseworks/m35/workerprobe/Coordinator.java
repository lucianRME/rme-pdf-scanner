package org.synapseworks.m35.workerprobe;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.system.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** Disposable test-only FD coordinator. This UID contains no consumer documents. */
public final class Coordinator extends Service {
  static final String API="M35_PROBE_V3",CALLER="org.synapseworks.m35.callerprobe",PERMISSION="org.synapseworks.m35.workerprobe.BIND_PROBE";
  final ExecutorService io=Executors.newSingleThreadExecutor();final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
  Job active;volatile boolean ready;volatile BoundaryExperiment boundary;
  static final class Job {
    final String nonce,kind,expected;final int uid,testMode;final ParcelFileDescriptor input,output;final IBinder callback;
    final AtomicBoolean finished=new AtomicBoolean(),cancelled=new AtomicBoolean();final long started=SystemClock.elapsedRealtime();
    volatile Thread preparation;volatile int pid;volatile ServiceConnection connection;volatile IBinder nativeBinder;volatile boolean bound;volatile String cancellation="CANCELLED";
    ScheduledFuture<?> deadline,monitor;IBinder.DeathRecipient death;
    long copied,initMs,convertMs,javaPeak,nativePeak,pssPeak,tempPeak;int pages,engineUid,enginePid,validatorUid,validatorPid,workerDeaths,guardBits,guardErrnos,protocolBits,vfsBits,callbackBits,outputProtection,traceCount,traceOp,traceRole,traceErrno;String traceCodes="";File root;IsolatedJob isolated;byte[]quarantineHash;
    Job(String n,String k,String h,int u,int mode,ParcelFileDescriptor i,ParcelFileDescriptor o,IBinder c){nonce=n;kind=k;expected=h;uid=u;testMode=mode;input=i;output=o;callback=c;}
  }
  public void onCreate(){super.onCreate();try{
    ActivityManager manager=(ActivityManager)getSystemService(ACTIVITY_SERVICE);java.util.List<ActivityManager.RunningAppProcessInfo> processes=manager.getRunningAppProcesses();
    if(processes==null)throw new IOException();
    for(ActivityManager.RunningAppProcessInfo process:processes)if(process.uid==android.os.Process.myUid()&&process.processName.equals(getPackageName()+":nativeprobe")){
      android.os.Process.killProcess(process.pid);long until=SystemClock.elapsedRealtime()+2000;boolean dead=false;
      while(SystemClock.elapsedRealtime()<until){try{Os.kill(process.pid,0);}catch(ErrnoException e){if(e.errno==OsConstants.ESRCH){dead=true;break;}throw e;}SystemClock.sleep(20);}
      if(!dead)throw new IOException();
    }
    File jobs=new File(getNoBackupFilesDir(),"jobs");boolean recovered=bytes(jobs)>0;IsolatedJob.recover(jobs);erase(jobs);if(recovered)outcome("RECOVERED");ready=true;
  }catch(Exception ignored){ready=false;}}
  boolean permitted(){int uid=Binder.getCallingUid();String[] packages=getPackageManager().getPackagesForUid(uid);return checkCallingPermission(PERMISSION)==PackageManager.PERMISSION_GRANTED&&getPackageManager().checkSignatures(uid,android.os.Process.myUid())==PackageManager.SIGNATURE_MATCH&&packages!=null&&packages.length==1&&packages[0].equals(CALLER);}
  public IBinder onBind(Intent intent){return new Binder(){protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){
    if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(API);return true;}
    if(reply==null||!permitted())return false;
    ParcelFileDescriptor input=null,output=null;
    try {
      data.enforceInterface(API);
      if(code==1){
        String kind=data.readString(),hash=data.readString();int testMode=data.readInt();input=data.readFileDescriptor();output=data.readFileDescriptor();IBinder callback=data.readStrongBinder();
        if(data.dataAvail()!=0||testMode<0||testMode>7||!Arrays.asList("docx","xlsx","pptx").contains(kind)||hash==null||!hash.matches("[a-f0-9]{64}")||input==null||output==null||callback==null){close(input);close(output);reply.writeString("INVALID");return true;}
        synchronized(Coordinator.this){
          if(!ready||active!=null||boundary!=null){close(input);close(output);reply.writeString(ready?"BUSY":"RECOVERY_FAILED");return true;}
          String nonce=UUID.randomUUID().toString().replace("-","");Job j=new Job(nonce,kind,hash,Binder.getCallingUid(),testMode,input,output,callback);
          j.death=()->requestCancel(j,"CALLER_DIED");callback.linkToDeath(j.death,0);active=j;
          j.deadline=timer.schedule(()->requestCancel(j,"TIMEOUT"),60,TimeUnit.SECONDS);io.execute(()->prepare(j));reply.writeString(nonce);return true;
        }
      }
      if(code==3){
        int denial=0;try{java.io.FileDescriptor fd=Os.open("/data/user/0/"+CALLER+"/files/uid-sentinel",OsConstants.O_RDONLY,0);Os.close(fd);}catch(ErrnoException e){denial=e.errno;}
        boolean denied=denial==OsConstants.EACCES||denial==OsConstants.ENOENT;
        reply.writeInt(android.os.Process.myUid());reply.writeString(denied?"UID_ACCESS_DENIED":"UID_BOUNDARY_FAILED");reply.writeInt(denial);return true;
      }
      if(code==4){int mode=data.readInt();IBinder callback=data.readStrongBinder();synchronized(Coordinator.this){if(mode<0||mode>3||callback==null||active!=null||boundary!=null||!ready){reply.writeInt(0);return true;}boundary=new BoundaryExperiment(Coordinator.this,callback,mode);boundary.start();reply.writeInt(1);return true;}}
      if(code==5){reply.writeInt(ready?1:0);synchronized(Coordinator.this){reply.writeInt(active==null?0:1);}reply.writeLong(bytes(new File(getNoBackupFilesDir(),"jobs")));File f=new File(getNoBackupFilesDir(),"last-outcome");String value=f.isFile()&&f.length()<=32?new String(Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.US_ASCII):"NONE";reply.writeString(value.matches("[A-Z_]{1,32}")?value:"UNKNOWN");return true;}
      if(code==2){String nonce=data.readString();Job j;synchronized(Coordinator.this){j=active;}if(j!=null&&j.uid==Binder.getCallingUid()&&j.nonce.equals(nonce))requestCancel(j,"CANCELLED");reply.writeInt(0);return true;}
      return false;
    }catch(Exception ignored){close(input);close(output);reply.writeString("IPC_FAILED");return true;}
  }};}
  void prepare(Job j){
    j.preparation=Thread.currentThread();
    try {
      guard(j);StructStat src=Os.fstat(j.input.getFileDescriptor()),dst=Os.fstat(j.output.getFileDescriptor());
      if(src.st_dev==dst.st_dev&&src.st_ino==dst.st_ino){finish(j,"SOURCE_ALIAS");return;}
      int sourceMode=Os.fcntlInt(j.input.getFileDescriptor(),OsConstants.F_GETFL,0)&OsConstants.O_ACCMODE;
      int outputMode=Os.fcntlInt(j.output.getFileDescriptor(),OsConstants.F_GETFL,0)&OsConstants.O_ACCMODE;
      if(!OsConstants.S_ISREG(src.st_mode)||!OsConstants.S_ISREG(dst.st_mode)||src.st_size<=0||src.st_size>Admission.INPUT||dst.st_size!=0||sourceMode!=OsConstants.O_RDONLY||outputMode!=OsConstants.O_RDWR||Os.lseek(j.input.getFileDescriptor(),0,OsConstants.SEEK_CUR)!=0||Os.lseek(j.output.getFileDescriptor(),0,OsConstants.SEEK_CUR)!=0){finish(j,"DESTINATION_REFUSED");return;}
      j.root=new File(new File(getNoBackupFilesDir(),"jobs"),j.nonce);if(!j.root.mkdirs())throw new IOException();
      // Fixed injection exists only in this disposable test package. It verifies
      // the same pre-copy low-space branch, not real filesystem exhaustion.
      long available=j.testMode==2?0:j.root.getUsableSpace();
      if(available<Admission.INPUT+256L*1024*1024+32L*1024*1024){finish(j,"STORAGE_LOW");return;}
      File source=new File(j.root,"input."+j.kind);MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
      try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(j.input);OutputStream out=new FileOutputStream(source)){
        byte[]b=new byte[16384];int n;while((n=in.read(b))!=-1){guard(j);total+=n;if(total>Admission.INPUT)throw new IOException();digest.update(b,0,n);out.write(b,0,n);}
      }
      guard(j);if(total!=src.st_size||!hex(digest.digest()).equals(j.expected)){finish(j,"SOURCE_CHANGED");return;}if(!source.setReadOnly())throw new IOException();
      Admission.validate(source,j.kind);guard(j);
      j.isolated=new IsolatedJob(this,j);j.isolated.prepare();
    }catch(Admission.Reject ex){finish(j,ex.code);}catch(Exception ignored){finish(j,j.cancelled.get()?j.cancellation:"INPUT_FAILED");}finally{j.preparation=null;}
  }
  void monitor(Job j){if(j.finished.get()||j.cancelled.get())return;try{
    long used=bytes(j.root)+(j.isolated!=null?j.isolated.reserved:0);j.tempPeak=Math.max(j.tempPeak,used);ActivityManager manager=(ActivityManager)getSystemService(ACTIVITY_SERVICE);
    Debug.MemoryInfo[]m=manager.getProcessMemoryInfo(new int[]{j.pid,android.os.Process.myPid()});long pss=0;for(Debug.MemoryInfo v:m)pss+=v.getTotalPss()*1024L;j.pssPeak=Math.max(j.pssPeak,pss);
    if(new File(j.root,"result.pdf").length()>32L*1024*1024)requestCancel(j,"OUTPUT_LIMIT");else if(used>256L*1024*1024)requestCancel(j,"TEMP_LIMIT");else if(pss>512L*1024*1024)requestCancel(j,"MEMORY_LIMIT");
  }catch(Exception ignored){requestCancel(j,"MONITOR_FAILED");}}
  void publish(Job j,String status){
    if(j.finished.get())return;if(!status.equals("OK")){finish(j,status);return;}
    try{
      guard(j);if(j.javaPeak>96L*1024*1024||j.pssPeak>512L*1024*1024){finish(j,"MEMORY_LIMIT");return;}
      File pdf=new File(j.root,"result.pdf");long size=pdf.length();if(size<=0||size>32L*1024*1024||j.pages<1||j.pages>10){finish(j,"OUTPUT_LIMIT");return;}
      if(!stop(j)){finish(j,"STOP_FAILED");return;}guard(j);
      StructStat dst=Os.fstat(j.output.getFileDescriptor());if(dst.st_size!=0||!OsConstants.S_ISREG(dst.st_mode)||Os.lseek(j.output.getFileDescriptor(),0,OsConstants.SEEK_CUR)!=0){finish(j,"DESTINATION_CHANGED");return;}
      MessageDigest digest=MessageDigest.getInstance("SHA-256");
      try(InputStream in=new FileInputStream(pdf)){byte[]b=new byte[65536];int n;while((n=in.read(b))!=-1){guard(j);digest.update(b,0,n);int offset=0;while(offset<n){guard(j);int wrote=Os.write(j.output.getFileDescriptor(),b,offset,n-offset);if(wrote<=0)throw new IOException();offset+=wrote;j.copied+=wrote;}}}
      Os.fsync(j.output.getFileDescriptor());Os.lseek(j.output.getFileDescriptor(),0,OsConstants.SEEK_SET);MessageDigest check=MessageDigest.getInstance("SHA-256");byte[]b=new byte[65536];int n;while((n=Os.read(j.output.getFileDescriptor(),b,0,b.length))>0){guard(j);check.update(b,0,n);}
      byte[]sourceHash=digest.digest();if(j.copied!=size||!MessageDigest.isEqual(sourceHash,check.digest())||(j.quarantineHash!=null&&!MessageDigest.isEqual(sourceHash,j.quarantineHash))){finish(j,"WRITE_FAILED");return;}finish(j,"OK");
    }catch(Exception ignored){finish(j,j.cancelled.get()?j.cancellation:"WRITE_FAILED");}
  }
  void guard(Job j)throws IOException {if(j.cancelled.get()||j.finished.get()||Thread.currentThread().isInterrupted())throw new IOException();}
  void requestCancel(Job j,String why){if(j.finished.get())return;if(j.cancelled.compareAndSet(false,true))j.cancellation=why;close(j.input);close(j.output);if(j.preparation!=null)j.preparation.interrupt();if(j.isolated==null&&j.pid>0&&j.pid!=android.os.Process.myPid())android.os.Process.killProcess(j.pid);try{io.execute(()->finish(j,j.cancellation));}catch(RejectedExecutionException ignored){}}
  boolean stop(Job j){
    if(j.monitor!=null)j.monitor.cancel(false);
    if(j.isolated!=null)return j.isolated.stop();
    if(j.pid>0&&j.pid!=android.os.Process.myPid())android.os.Process.killProcess(j.pid);
    long end=SystemClock.elapsedRealtime()+2000;while(j.nativeBinder!=null&&j.nativeBinder.isBinderAlive()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(20);
    boolean dead=j.nativeBinder==null||!j.nativeBinder.isBinderAlive();
    if(j.bound&&j.connection!=null){try{unbindService(j.connection);}catch(Exception ignored){}j.bound=false;}if(dead)j.pid=0;return dead;
  }
  void finish(Job j,String status){
    if(!j.finished.compareAndSet(false,true))return;if(j.deadline!=null)j.deadline.cancel(false);
    boolean stopped=stop(j);close(j.input);close(j.output);j.callback.unlinkToDeath(j.death,0);
    boolean clean=stopped;try{if(stopped)erase(j.root);}catch(Exception ignored){clean=false;}if(!clean)ready=false;
    try{outcome(status);}catch(Exception ignored){ready=false;}
    synchronized(this){if(active==j)active=null;}
    Parcel p=Parcel.obtain();try{p.writeInterfaceToken("M35_RESULT_V8");p.writeString(j.nonce);p.writeString(status);p.writeInt(j.pages);p.writeLong(j.initMs);p.writeLong(j.convertMs);p.writeLong(j.javaPeak);p.writeLong(j.nativePeak);p.writeLong(j.pssPeak);p.writeLong(j.copied);p.writeInt(clean?1:0);p.writeLong(j.tempPeak);p.writeInt(j.engineUid);p.writeInt(j.enginePid);p.writeInt(j.validatorUid);p.writeInt(j.validatorPid);p.writeInt(j.workerDeaths);p.writeInt(j.guardBits);p.writeInt(j.guardErrnos);p.writeInt(j.protocolBits);p.writeInt(j.vfsBits);p.writeInt(j.callbackBits);p.writeInt(j.outputProtection);p.writeInt(j.traceCount);p.writeInt(j.traceOp);p.writeInt(j.traceRole);p.writeInt(j.traceErrno);p.writeString(j.traceCodes);j.callback.transact(1,p,null,IBinder.FLAG_ONEWAY);}catch(Exception ignored){}finally{p.recycle();}
  }
  static void close(ParcelFileDescriptor p){if(p!=null)try{p.close();}catch(Exception ignored){}}
  void outcome(String status)throws IOException {if(!status.matches("[A-Z_]{1,32}"))throw new IOException();try(FileOutputStream out=new FileOutputStream(new File(getNoBackupFilesDir(),"last-outcome"))){out.write(status.getBytes(java.nio.charset.StandardCharsets.US_ASCII));out.getFD().sync();}}
  static String hex(byte[]b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
  static long bytes(File f)throws IOException {
    if(f==null||!f.exists())return 0;final long[]n={0};final int[]entries={0};
    Files.walkFileTree(f.toPath(),EnumSet.noneOf(FileVisitOption.class),32,new SimpleFileVisitor<Path>(){
      void guard()throws IOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();if(++entries[0]>10000)throw new IOException();}
      public FileVisitResult preVisitDirectory(Path p,BasicFileAttributes a)throws IOException{guard();return FileVisitResult.CONTINUE;}
      public FileVisitResult visitFile(Path p,BasicFileAttributes a)throws IOException{guard();if(a.isDirectory())throw new IOException();n[0]+=a.size();return FileVisitResult.CONTINUE;}
      public FileVisitResult visitFileFailed(Path p,IOException failure)throws IOException{guard();if(failure instanceof NoSuchFileException)return FileVisitResult.CONTINUE;throw failure;}
    });return n[0];
  }
  static void erase(File f)throws IOException {if(f==null||!f.exists())return;Files.walkFileTree(f.toPath(),new SimpleFileVisitor<Path>(){public FileVisitResult visitFile(Path p,BasicFileAttributes a)throws IOException{Files.delete(p);return FileVisitResult.CONTINUE;}public FileVisitResult postVisitDirectory(Path p,IOException e)throws IOException{if(e!=null)throw e;Files.delete(p);return FileVisitResult.CONTINUE;}});}
  public void onDestroy(){Job j;synchronized(this){j=active;}if(j!=null)requestCancel(j,j.callback.pingBinder()?"CLOSED":"CALLER_DIED");io.shutdown();timer.shutdownNow();super.onDestroy();}
}
