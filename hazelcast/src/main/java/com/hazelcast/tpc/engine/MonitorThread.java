/*
 * Copyright (c) 2008-2022, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.tpc.engine;

import com.hazelcast.internal.util.counters.SwCounter;
import com.hazelcast.tpc.engine.nio.NioAsyncSocket;
import com.hazelcast.tpc.engine.iouring.IOUringAsyncSocket;

import java.util.HashMap;
import java.util.Map;

import static java.lang.System.currentTimeMillis;

public final class MonitorThread extends Thread {

    private final Eventloop[] eventloops;
    private final boolean silent;
    private volatile boolean shutdown = false;
    private long prevMillis = currentTimeMillis();
    // There is a memory leak on the counters. When channels die, counters are not removed.
    private final Map<SwCounter, LongHolder> prevMap = new HashMap<>();

    public MonitorThread(Eventloop[] eventloops, boolean silent) {
        super("MonitorThread");
        this.eventloops = eventloops;
        this.silent = silent;
    }

    static class LongHolder {
        public long value;
    }

    @Override
    public void run() {
        try {
            long nextWakeup = currentTimeMillis() + 5000;
            while (!shutdown) {
                try {
                    long now = currentTimeMillis();
                    long remaining = nextWakeup - now;
                    if (remaining > 0) {
                        Thread.sleep(remaining);
                    }
                } catch (InterruptedException e) {
                    return;
                }

                nextWakeup += 5000;

                long currentMillis = currentTimeMillis();
                long elapsed = currentMillis - prevMillis;
                this.prevMillis = currentMillis;

                for (Eventloop eventloop : eventloops) {
//                    for (AsyncSocket socket : eventloop.resources()) {
//                        monitor(socket, elapsed);
//                    }
                }

                if (!silent) {
                    for (Eventloop eventloop : eventloops) {
                        monitor(eventloop, elapsed);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void monitor(Eventloop eventloop, long elapsed) {
        //log(reactor + " request-count:" + reactor.requests.get());
        //log(reactor + " channel-count:" + reactor.channels().size());

//        long requests = reactor.requests.get();
//        LongHolder prevRequests = getPrev(reactor.requests);
//        long requestsDelta = requests - prevRequests.value;
        //log(reactor + " " + thp(requestsDelta, elapsed) + " requests/second");
        //prevRequests.value = requests;
//
//        log("head-block:" + reactor.reactorQueue.head_block
//                + " tail:" + reactor.reactorQueue.tail.get()
//                + " dirtyHead:" + reactor.reactorQueue.dirtyHead.get()
//                + " cachedTail:" + reactor.reactorQueue.cachedTail);
////
    }

    private void log(String s) {
        System.out.println("[monitor] " + s);
    }

    private void monitor(AsyncSocket socket, long elapsed) {
        long packetsRead = socket.framesRead.get();
        LongHolder prevPacketsRead = getPrev(socket.framesRead);
        long packetsReadDelta = packetsRead - prevPacketsRead.value;

        if (!silent) {
            log(socket + " " + thp(packetsReadDelta, elapsed) + " packets/second");

            long bytesRead = socket.bytesRead.get();
            LongHolder prevBytesRead = getPrev(socket.bytesRead);
            long bytesReadDelta = bytesRead - prevBytesRead.value;
            log(socket + " " + thp(bytesReadDelta, elapsed) + " bytes-read/second");
            prevBytesRead.value = bytesRead;

            long bytesWritten = socket.bytesWritten.get();
            LongHolder prevBytesWritten = getPrev(socket.bytesWritten);
            long bytesWrittenDelta = bytesWritten - prevBytesWritten.value;
            log(socket + " " + thp(bytesWrittenDelta, elapsed) + " bytes-written/second");
            prevBytesWritten.value = bytesWritten;

            long handleOutboundCalls = socket.handleWriteCnt.get();
            LongHolder prevHandleOutboundCalls = getPrev(socket.handleWriteCnt);
            long handleOutboundCallsDelta = handleOutboundCalls - prevHandleOutboundCalls.value;
            log(socket + " " + thp(handleOutboundCallsDelta, elapsed) + " handleOutbound-calls/second");
            prevHandleOutboundCalls.value = handleOutboundCalls;

            long readEvents = socket.readEvents.get();
            LongHolder prevReadEvents = getPrev(socket.readEvents);
            long readEventsDelta = readEvents - prevReadEvents.value;
            log(socket + " " + thp(readEventsDelta, elapsed) + " read-events/second");
            prevReadEvents.value = readEvents;

            log(socket + " " + (packetsReadDelta * 1.0f / (handleOutboundCallsDelta + 1)) + " packets/handleOutbound-call");
            log(socket + " " + (packetsReadDelta * 1.0f / (readEventsDelta + 1)) + " packets/read-events");
            log(socket + " " + (bytesReadDelta * 1.0f / (readEventsDelta + 1)) + " bytes-read/read-events");
        }
        prevPacketsRead.value = packetsRead;

        if (packetsReadDelta == 0 || true) {
            if (socket instanceof NioAsyncSocket) {
                NioAsyncSocket c = (NioAsyncSocket) socket;
                boolean hasData = !c.unflushedFrames.isEmpty() || !c.ioVector.isEmpty();
                //if (nioChannel.flushThread.get() == null && hasData) {
                log(socket + " is stuck: unflushed-frames:" + c.unflushedFrames.size()
                        + " ioVector.empty:" + c.ioVector.isEmpty()
                        + " flushed:" + c.flushThread.get()
                        + " eventloop.contains:" + c.eventloop.concurrentRunQueue.contains(c));
                //}
            } else if (socket instanceof IOUringAsyncSocket) {
                IOUringAsyncSocket c = (IOUringAsyncSocket) socket;
                boolean hasData = !c.unflushedFrames.isEmpty() || !c.ioVector.isEmpty();
                //if (c.flushThread.get() == null && hasData) {
                log(socket + " is stuck: unflushed-frames:" + c.unflushedFrames.size()
                        + " ioVector.empty:" + c.ioVector.isEmpty()
                        + " flushed:" + c.flushThread.get()
                        + " eventloop.contains:" + c.eventloop().concurrentRunQueue.contains(c));
                //}
            }
        }
    }

    private LongHolder getPrev(SwCounter counter) {
        LongHolder prev = prevMap.get(counter);
        if (prev == null) {
            prev = new LongHolder();
            prevMap.put(counter, prev);
        }
        return prev;
    }

    public static float thp(long delta, long elapsed) {
        return (delta * 1000f) / elapsed;
    }

    public void shutdown() {
        shutdown = true;
        interrupt();
    }
}
