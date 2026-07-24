import Darwin
import SwiftUI
import UIKit

final class PressureAllocator: ObservableObject {
    @Published private(set) var allocatedBytes: UInt64 = 0
    @Published private(set) var targetBytes: UInt64 = 0
    @Published private(set) var state = "Preparing memory pressure…"

    private let lock = NSLock()
    private var mappings: [(UnsafeMutableRawPointer, Int)] = []
    private var abortReason: String?

    func start() {
        let arguments = ProcessInfo.processInfo.arguments
        let fraction = argument("--fraction", in: arguments).flatMap(Double.init) ?? 0.15
        let holdSeconds = argument("--hold-seconds", in: arguments).flatMap(Int.init) ?? 6
        let physicalMemory = ProcessInfo.processInfo.physicalMemory
        let requestedBytes: UInt64
        if let megabytes = argument("--megabytes", in: arguments).flatMap(UInt64.init) {
            requestedBytes = megabytes * 1_048_576
        } else {
            requestedBytes = UInt64(
                Double(physicalMemory) * min(max(fraction, 0.1), 0.9)
            )
        }
#if targetEnvironment(simulator)
        let safetyCap = physicalMemory
#else
        // A stock-device helper has no entitlement to consume a fixed fraction
        // of all device RAM. Keep the attempt under both 1/6 of physical memory
        // and 512 MiB so the default does not intentionally drive jetsam.
        let safetyCap = min(physicalMemory / 6, 512 * 1_048_576)
#endif
        targetBytes = min(requestedBytes, safetyCap)

        NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil,
            queue: nil
        ) { [weak self] _ in
            self?.requestAbort(reason: "memory-warning")
        }

        let boundedTargetBytes = targetBytes
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.allocateAndTouch(
                targetBytes: boundedTargetBytes,
                holdSeconds: holdSeconds
            )
        }
    }

    private func argument(_ name: String, in arguments: [String]) -> String? {
        guard let index = arguments.firstIndex(of: name), arguments.indices.contains(index + 1) else {
            return nil
        }
        return arguments[index + 1]
    }

    private func requestAbort(reason: String) {
        lock.lock()
        if abortReason == nil {
            abortReason = reason
        }
        lock.unlock()
        DispatchQueue.main.async { [weak self] in
            self?.state = "Aborting: \(reason)"
        }
    }

    private func currentAbortReason() -> String? {
        lock.lock()
        defer { lock.unlock() }
        return abortReason
    }

    private func retain(_ pointer: UnsafeMutableRawPointer, size: Int) {
        lock.lock()
        mappings.append((pointer, size))
        lock.unlock()
    }

    private func releaseMappings() {
        lock.lock()
        let retainedMappings = mappings
        mappings.removeAll()
        lock.unlock()
        for (pointer, length) in retainedMappings {
            munmap(pointer, length)
        }
    }

    private func abortAndExit(
        reason: String,
        allocatedBytes: UInt64,
        targetBytes: UInt64
    ) -> Never {
        print(
            "CACHE_PRESSURE_ABORTED allocated_bytes=\(allocatedBytes) " +
                "target_bytes=\(targetBytes) reason=\(reason)"
        )
        fflush(stdout)
        releaseMappings()
        exit(2)
    }

    private func allocateAndTouch(targetBytes: UInt64, holdSeconds: Int) {
        let pageSize = Int(getpagesize())
        let chunkSize = 8 * 1024 * 1024
        var allocated: UInt64 = 0

        while allocated < targetBytes && currentAbortReason() == nil {
            let remaining = targetBytes - allocated
            let length = Int(min(UInt64(chunkSize), remaining))
            guard let pointer = mmap(
                nil,
                length,
                PROT_READ | PROT_WRITE,
                MAP_ANON | MAP_PRIVATE,
                -1,
                0
            ), pointer != MAP_FAILED else {
                requestAbort(reason: "mmap-failed")
                break
            }
            for offset in stride(from: 0, to: length, by: pageSize) {
                pointer.storeBytes(of: UInt8(truncatingIfNeeded: offset), toByteOffset: offset, as: UInt8.self)
            }
            retain(pointer, size: length)
            allocated += UInt64(length)
            DispatchQueue.main.async { [weak self] in
                self?.allocatedBytes = allocated
                self?.state = "Touched \(allocated / 1_048_576) MiB"
            }
        }

        if let reason = currentAbortReason() {
            abortAndExit(
                reason: reason,
                allocatedBytes: allocated,
                targetBytes: targetBytes
            )
        }

        print(
            "CACHE_PRESSURE_READY allocated_bytes=\(allocated) " +
                "target_bytes=\(targetBytes) hold_seconds=\(holdSeconds)"
        )
        fflush(stdout)
        DispatchQueue.main.async { [weak self] in
            self?.state = "Holding \(allocated / 1_048_576) MiB for \(holdSeconds) seconds"
        }
        let holdDeadline = DispatchTime.now().uptimeNanoseconds +
            UInt64(max(holdSeconds, 1)) * 1_000_000_000
        while DispatchTime.now().uptimeNanoseconds < holdDeadline {
            if let reason = currentAbortReason() {
                abortAndExit(
                    reason: reason,
                    allocatedBytes: allocated,
                    targetBytes: targetBytes
                )
            }
            usleep(100_000)
        }
        if let reason = currentAbortReason() {
            abortAndExit(
                reason: reason,
                allocatedBytes: allocated,
                targetBytes: targetBytes
            )
        }
        print("CACHE_PRESSURE_COMPLETE allocated_bytes=\(allocated)")
        fflush(stdout)
        releaseMappings()
        exit(0)
    }
}

struct ContentView: View {
    @StateObject private var allocator = PressureAllocator()

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "memorychip")
                .font(.system(size: 52))
                .foregroundStyle(.orange)
            Text("Cache Pressure")
                .font(.largeTitle.bold())
            Text(allocator.state)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            ProgressView(
                value: Double(allocator.allocatedBytes),
                total: Double(max(allocator.targetBytes, 1))
            )
            Text("\(allocator.allocatedBytes / 1_048_576) MiB allocated")
                .font(.system(.body, design: .monospaced))
        }
        .padding(28)
        .onAppear { allocator.start() }
    }
}

@main
struct CachePressureApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
