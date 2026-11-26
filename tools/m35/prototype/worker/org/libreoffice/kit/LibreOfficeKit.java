/*
 * Based on the LibreOffice Android JNI bridge.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * RME adapted the bridge for explicit initialization and capability
 * controls, without the viewer, logging or stdio forwarding.
 */
package org.libreoffice.kit;
import android.content.Context;
import java.nio.ByteBuffer;
import java.io.File;
/** Direct upstream JNI interface. No stock viewer, logging or stdio forwarding. */
public final class LibreOfficeKit {
  private LibreOfficeKit() {}
  private static native boolean initializeNative(String data, String cache, String apk, android.content.res.AssetManager assets);
  public static native ByteBuffer getLibreOfficeKitHandle();
  public static native boolean configureCapabilities(String[] names,int[] descriptors,int[] scratch,int output,int trace,String apk);
  public static native void observeCapabilities(int trace,String root);
  public static native long[] capabilityStats();
  public static native int capabilityControls();
  public static void loadLibraries() {
    String[] libraries={"nspr4","plds4","plc4","nssutil3","freebl3","sqlite3","softokn3","nss3","nssckbi","nssdbm3","smime3","ssl3","lo-native-code"};
    for(String library:libraries)System.loadLibrary(library);
  }
  public static boolean initialize(Context context, File root, File scratch) {
    loadLibraries();
    return initializeNative(root.getAbsolutePath(),scratch.getAbsolutePath(),context.getApplicationInfo().sourceDir,context.getAssets());
  }
}
