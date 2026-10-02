package dev.primept;

import java.nio.file.Path;
import dev.primept.capture.Packets;

public final class CpuDiagnosticsSmoke {
    public static void main(String[] args) throws Exception {
        try (var bridge = new NativeBridge(Path.of(args[0]))) {
            var initial = bridge.cpuDiagnostics();
            if (!initial.contains("available=false"))
                throw new AssertionError(initial);
            bridge.reset(1);
            if (!bridge.cpuDiagnostics().equals(initial))
                throw new AssertionError("Source submission cannot fabricate renderer timings");
            var wrongThread = new Thread(() -> {
                try {
                    bridge.cpuDiagnostics();
                    throw new AssertionError("Wrong thread accepted");
                } catch (IllegalStateException expected) {}
            });
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            wrongThread.setUncaughtExceptionHandler((thread, error) -> failure.set(error));
            wrongThread.start();
            wrongThread.join();
            if (failure.get() != null)
                throw new AssertionError(failure.get());
        }
        System.out.println(
                "PRIME_CPU_DIAGNOSTICS_FFM_OK: ABI, owned UTF-8, unavailable state and owner thread; no GPU");
    }
}
