package com.sifli.ezip;

/**
 * Minimal JNI declaration for the locally supplied SiFli encoder.
 *
 * The native ABI fixes this class and method name. No Wearfit application
 * code is included here.
 */
public final class sifliEzipUtil {
    static {
        System.loadLibrary("ezip");
    }

    private sifliEzipUtil() {}

    private static native byte[] png2EzipWithTypeNDK(
            byte[] png,
            long length,
            String colorFormat,
            int color,
            int binaryFormat,
            int boardType
    );

    public static synchronized byte[] encodeRgb565Alpha(byte[] png) {
        if (png == null || png.length < 8
                || png[0] != (byte) 0x89
                || png[1] != 0x50
                || png[2] != 0x4E
                || png[3] != 0x47) {
            throw new IllegalArgumentException("SiFli eZip input must be PNG data");
        }
        byte[] result = png2EzipWithTypeNDK(
                png, png.length, "rgb565a", 0, 1, 0
        );
        if (result == null || result.length < 8) {
            throw new IllegalStateException("SiFli eZip encoder returned no data");
        }
        return result;
    }
}
