# Release shrinking is enabled. AndroidX, ML Kit, and PdfBox ship their own consumer rules.
# Keep this file narrow: do not disable optimization or add broad keep rules without a verified
# runtime need, because that would undermine reproducible release-size checks.

# PdfBox references an optional JPEG-2000 codec that is not packaged by RME PDF Scanner. Scanned JPEG
# pages do not use this codec; suppress only the absent optional types so R8 can complete.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
