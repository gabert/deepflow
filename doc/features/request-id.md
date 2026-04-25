# Request ID

The request ID (`RI` tag) groups all method calls that belong to a single
request. Every entry and exit record within a request carries the same RI
value.

## How it works

A global `AtomicLong` counter generates request IDs. Each thread maintains:

- **depth** (`ThreadLocal<int[]>`) -- incremented on entry, decremented on exit
- **current request ID** (`ThreadLocal<long[]>`) -- the active request ID

When a method is entered and depth is 0 (root call), a new request ID is
assigned from the counter. All subsequent nested calls on the same thread
share this request ID until the root call returns and depth drops back to 0.

```
Thread "main":

recordEntry(foo)    depth 0->1, new RI=7
  recordEntry(bar)  depth 1->2, same RI=7
  recordExit(bar)   depth 2->1
  recordEntry(baz)  depth 1->2, same RI=7
  recordExit(baz)   depth 2->1
recordExit(foo)     depth 1->0

recordEntry(next)   depth 0->1, new RI=8
recordExit(next)    depth 1->0
```

## Entry and exit correlation

Both entry (TS block) and exit (TE block) records carry the request ID:

```
TS;1000
MS;com.example::Service.process(int) -> void [public]
RI;7
...
TE;2000
RI;7
RT;VALUE
RE;"result"
```

This allows correlating which exit belongs to which entry in a multiplexed
stream, and grouping all records of a single request together.

## Nesting

Within a single request, calls nest like parentheses. The depth is implicit
from the TS/TE ordering:

```
TS;1000  RI;7  MS;outer()       <- depth 0
TS;1100  RI;7  MS;middle()      <- depth 1
TS;1200  RI;7  MS;inner()       <- depth 2
TE;1300  RI;7                   <- inner returns
TE;1400  RI;7                   <- middle returns
TE;1500  RI;7                   <- outer returns
```

## Cross-thread propagation

When `propagate_request_id=true` (default), the agent instruments
`ThreadPoolExecutor.execute()` and `ForkJoinPool.execute()/submit()` to
propagate the request ID from the submitting thread to the executing thread.

This is implemented via `PropagatingRunnable` and `PropagatingCallable`
wrappers that capture the submitting thread's request ID and restore it
in the executing thread.

```
Thread "http-handler":
  recordEntry(handleRequest)   RI=10
    executor.submit(task)      <- PropagatingRunnable captures RI=10

Thread "worker-1":
  PropagatingRunnable.run()    <- restores RI=10
    recordEntry(asyncWork)     RI=10 (same request!)
    recordExit(asyncWork)
```

Without propagation, the worker thread would start a new request ID,
breaking the correlation.

## Independent threads

Threads that are not spawned from an instrumented context (e.g. scheduled
tasks, timer threads) generate their own independent request IDs. Each
thread's depth counter starts at 0.

## Implementation

Key source locations:

- `DeepFlowAdvice.CURRENT_REQUEST_ID` -- per-thread current request ID
- `DeepFlowAdvice.DEPTH` -- per-thread call depth
- `DeepFlowAdvice.REQUEST_COUNTER` -- global atomic counter
- `RecordWriter.methodStart()` / `methodEnd()` -- requestId in binary payload
- `RecordRenderer` -- renders RI tag from both METHOD_START and METHOD_END
