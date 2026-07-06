package com.github.gabert.arachna.trace.recorder.buffer;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link RecordBuffer} with one shard (queue) per producer thread.
 *
 * <p>A single shared queue makes every traced thread contend on the same
 * tail CAS. Here each producer enqueues into its own shard — uncontended by
 * construction — and the single drainer thread round-robins across shards.
 * Per-thread record order is preserved (each shard is FIFO and has exactly
 * one producer and one consumer); global interleaving across threads was
 * never guaranteed by the shared queue either, and downstream consumers
 * order by {@code SQ}/{@code CI}, not arrival.</p>
 *
 * <p><b>Bounding.</b> With {@code maxRecordsPerThread > 0} a shard that
 * reaches the cap drops the <em>new</em> record instead of growing — the
 * host application's heap is protected when the drain side stalls. Drops
 * are never silent: the first drop per shard and every {@value
 * #DROP_LOG_INTERVAL}th after it are logged, and {@link #droppedCount()}
 * exposes the running total (logged again at shutdown by the manager).
 * With {@code 0} (the default) shards are unbounded, matching
 * {@link UnboundedRecordBuffer} semantics.</p>
 *
 * <p><b>Long-running agents.</b> Shards of dead threads are drained
 * normally and pruned from the registry once empty, so thread churn does
 * not accumulate empty queues.</p>
 */
public final class ShardedRecordBuffer implements RecordBuffer {
    private static final int DROP_LOG_INTERVAL = 10_000;

    private final int maxRecordsPerThread;
    private final CopyOnWriteArrayList<Shard> shards = new CopyOnWriteArrayList<>();
    private final ThreadLocal<Shard> localShard = ThreadLocal.withInitial(this::registerShard);
    private final AtomicLong dropped = new AtomicLong();

    /** The drainer's round-robin cursor — only the single drainer thread uses it. */
    private int pollCursor;

    public ShardedRecordBuffer(int maxRecordsPerThread) {
        this.maxRecordsPerThread = maxRecordsPerThread;
    }

    @Override
    public void offer(byte[] record) {
        Shard shard = localShard.get();
        if (maxRecordsPerThread > 0 && shard.size.get() >= maxRecordsPerThread) {
            long total = dropped.incrementAndGet();
            if (total == 1 || total % DROP_LOG_INTERVAL == 0) {
                System.err.println("[ArachnaTrace] Record buffer full ("
                        + maxRecordsPerThread + " records) on thread '"
                        + Thread.currentThread().getName() + "' — dropping; "
                        + total + " record(s) dropped so far this run");
            }
            return;
        }
        shard.queue.add(record);
        shard.size.incrementAndGet();
    }

    @Override
    public byte[] poll() {
        int count = shards.size();
        for (int i = 0; i < count; i++) {
            Shard shard = shards.get((pollCursor + i) % count);
            byte[] record = shard.queue.poll();
            if (record != null) {
                shard.size.decrementAndGet();
                // Resume after this shard next time so one busy thread
                // cannot starve the others.
                pollCursor = (pollCursor + i + 1) % count;
                return record;
            }
            if (shard.owner.get() == null) {
                // Dead thread, empty shard — prune so thread churn does not
                // accumulate registry entries over a long run.
                shards.remove(shard);
                count = shards.size();
                if (count == 0) return null;
            }
        }
        return null;
    }

    @Override
    public int size() {
        int total = 0;
        for (Shard shard : shards) {
            total += shard.size.get();
        }
        return total;
    }

    @Override
    public boolean isEmpty() {
        for (Shard shard : shards) {
            if (!shard.queue.isEmpty()) return false;
        }
        return true;
    }

    /** Total records dropped because a shard hit its bound. */
    public long droppedCount() {
        return dropped.get();
    }

    private Shard registerShard() {
        Shard shard = new Shard(Thread.currentThread());
        shards.add(shard);
        return shard;
    }

    private static final class Shard {
        final ConcurrentLinkedQueue<byte[]> queue = new ConcurrentLinkedQueue<>();
        // ConcurrentLinkedQueue.size() is O(n); the bound check needs O(1).
        final AtomicInteger size = new AtomicInteger();
        final WeakReference<Thread> owner;

        Shard(Thread owner) {
            this.owner = new WeakReference<>(owner);
        }
    }
}
