package org.synapseworks.m35.workerprobe;
/** Finite private profile; original20MiB input/256MiB aggregate ceilings retained. */
final class CapabilityProfile {
  static final int MIB=1024*1024,RESOURCES=26;
  static final long WRITABLE=208L*MIB+4096,READ_ONLY=40L*MIB+8192,UNPACKED=20L*MIB;
  static int scratchSize(int index){if(index<0||index>=29)throw new IllegalArgumentException();return index<24?MIB:index==28?24*MIB:32*MIB;}
  // No system directories, includes, remote URLs or fixture-specific names.
  // A requested installed family stays first; generic aliases control fallback.
  static final String FONT_CONFIG="<?xml version=\"1.0\"?><fontconfig><dir>/m35/job/user/fonts</dir><cachedir>/m35/job/cache/font-cache</cachedir>"
    +"<alias binding=\"strong\"><family>serif</family><prefer><family>Linux Libertine G</family><family>Liberation Serif</family></prefer></alias>"
    +"<alias binding=\"strong\"><family>sans-serif</family><prefer><family>Liberation Sans</family></prefer></alias>"
    +"<alias binding=\"strong\"><family>monospace</family><prefer><family>Liberation Mono</family></prefer></alias>"
    +"<match target=\"pattern\"><test qual=\"all\" name=\"family\" compare=\"not_eq\"><string>sans-serif</string></test><test qual=\"all\" name=\"family\" compare=\"not_eq\"><string>serif</string></test><test qual=\"all\" name=\"family\" compare=\"not_eq\"><string>monospace</string></test><edit name=\"family\" mode=\"append_last\" binding=\"weak\"><string>sans-serif</string></edit></match></fontconfig>";
}
