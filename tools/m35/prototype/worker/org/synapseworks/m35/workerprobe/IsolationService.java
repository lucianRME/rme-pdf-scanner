package org.synapseworks.m35.workerprobe;
import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.libreoffice.kit.LibreOfficeKit;
/** Capability/bootstrap experiment only. The rendering service is NOT yet ported. */
public final class IsolationService extends Service {
  static final String API="M35_ISOLATED_EXPERIMENT_V1";
  final AtomicBoolean used=new AtomicBoolean();
  public IBinder onBind(Intent intent){return new Binder(){protected boolean onTransact(int code,Parcel p,Parcel r,int flags){
    if(code==INTERFACE_TRANSACTION){r.writeString(API);return true;}
    if(code!=1||Binder.getCallingUid()!=getApplicationInfo().uid||!used.compareAndSet(false,true))return false;
    try{
      p.enforceInterface(API);int mode=p.readInt();String[]paths=p.createStringArray();String root=p.readString();
      ParcelFileDescriptor source=p.readFileDescriptor(),output=p.readFileDescriptor();IBinder callback=p.readStrongBinder();
      if(mode<0||mode>3||paths==null||paths.length!=4||source==null||output==null||callback==null||p.dataAvail()!=0)throw new IOException();
      Runnable task=()->{
        try(ParcelFileDescriptor s=source;ParcelFileDescriptor o=output;ParcelFileDescriptor trace=ParcelFileDescriptor.adoptFd(Engine.shared(4096))){
          if(!Engine.quiet())throw new IOException();
          Parcel offer=Parcel.obtain(),accepted=Parcel.obtain();try{offer.writeInterfaceToken(API);offer.writeFileDescriptor(trace.getFileDescriptor());if(!callback.transact(2,offer,accepted,0)||accepted.readInt()!=1)throw new IOException();}finally{offer.recycle();accepted.recycle();}
          int bits=Engine.isolation(paths,s.getFd(),o.getFd());send(callback,"BOUNDARY_RESULT",bits);
          if(mode==1||mode==2){send(callback,"NATIVE_ENTERED",bits);Engine.hang();}
          else if(mode==3){
            // The unported engine needs path opens for its private profile/fonts.
            // Attempt the actual pinned bootstrap, without loading any document.
            LibreOfficeKit.loadLibraries();LibreOfficeKit.observeCapabilities(trace.getFd(),root);
            boolean initialized=LibreOfficeKit.initialize(IsolationService.this,new File(root),new File(root,"cache"));
            send(callback,initialized?"BOOTSTRAP_RETURNED_TRUE":"BOOTSTRAP_REFUSED",bits);
          }
        }catch(Throwable ignored){send(callback,"ISOLATED_FAILED",0);}
      };
      if(mode==2)new Handler(getMainLooper()).post(task);else new Thread(task,"m35-isolated-experiment").start();
      r.writeInt(1);return true;
    }catch(Exception ignored){r.writeInt(0);return true;}
  }};}
  void send(IBinder callback,String status,int bits){Parcel p=Parcel.obtain();try{p.writeInterfaceToken(API);p.writeString(status);p.writeInt(bits);callback.transact(1,p,null,IBinder.FLAG_ONEWAY);}catch(Exception ignored){}finally{p.recycle();}}
}
