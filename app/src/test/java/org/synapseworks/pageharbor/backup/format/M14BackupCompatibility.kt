package org.synapseworks.pageharbor.backup.format

/** Frozen M14 header admission before grouped signatures, independent of current constants. */
internal object M14BackupCompatibility {
 fun accepts(manifest:BackupManifest)=manifest.formatVersion in 1..5 && manifest.minimumReaderVersion in 1..5 && manifest.minimumReaderVersion==manifest.formatVersion
}
