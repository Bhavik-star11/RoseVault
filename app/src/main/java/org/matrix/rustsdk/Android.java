package org.matrix.rustsdk;

/** Java declaration for the Apache-2.0 SDK's exported Android JNI entry point. */
public final class Android {
    private Android() {}
    static { System.loadLibrary("matrix_sdk_ffi"); }
    public static native void init(android.content.Context context);
}
