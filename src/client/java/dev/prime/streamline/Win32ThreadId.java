package dev.prime.streamline;

import dev.prime.render.vulkan.natives.NativeLibraries;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/** kernel32!GetCurrentThreadId for ReflexOptions.idThread; 0 when unavailable. */
@SuppressWarnings("restricted")
final class Win32ThreadId {
    private static final MethodHandle GET_CURRENT_THREAD_ID = resolve();

    private Win32ThreadId() {
    }

    private static MethodHandle resolve() {
        if (!NativeLibraries.isWindowsX64()) {
            return null;
        }
        try {
            // The shared arena intentionally stays open: the lookup must outlive the process.
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.ofShared());
            MemorySegment symbol = kernel32.find("GetCurrentThreadId").orElseThrow();
            return Linker.nativeLinker().downcallHandle(symbol, FunctionDescriptor.of(JAVA_INT));
        } catch (Throwable failure) {
            return null;
        }
    }

    static int current() {
        MethodHandle handle = GET_CURRENT_THREAD_ID;
        if (handle == null) {
            return 0;
        }
        try {
            return (int) handle.invokeExact();
        } catch (Throwable failure) {
            return 0;
        }
    }
}
