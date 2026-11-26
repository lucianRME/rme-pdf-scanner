package org.synapseworks.m35.workerprobe;
import java.nio.ByteBuffer;
final class Engine {
  static {System.loadLibrary("m35-probe");}
  static native boolean quiet();
  static native int convert(ByteBuffer office,String input,String output);
  static native int isolation(String[] paths,int source,int output);
  static native void hang();
  static native int sealed(int capacity,boolean finalSeal);
  static native boolean sealOutput(int descriptor);
  static native boolean capacityValid(int descriptor,int capacity,int seals);
  static native int shared(int capacity);
  static native boolean sharedValid(int descriptor,int capacity);
  static native byte[] readShared(int descriptor,int capacity,int offset,int length);
  static native int freezeOutput(int descriptor);
  static native boolean independent(int[] descriptors);
  static native boolean sandbox();
  static native long engineGuards(String[] paths,int source,int smallScratch,int output);
  static native int convertGated(ByteBuffer office,String input,String output,boolean gate);
  static volatile Runnable entered;
  static void nativeEntered(){Runnable callback=entered;if(callback!=null)callback.run();}
}
