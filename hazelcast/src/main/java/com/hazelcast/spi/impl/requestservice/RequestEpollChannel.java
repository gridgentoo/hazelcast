package com.hazelcast.spi.impl.requestservice;

import com.hazelcast.spi.impl.engine.frame.Frame;
import com.hazelcast.spi.impl.engine.frame.FrameAllocator;
import io.netty.channel.epoll.EpollChannel;

import java.nio.ByteBuffer;

import static com.hazelcast.internal.nio.Bits.INT_SIZE_IN_BYTES;
import static com.hazelcast.spi.impl.engine.frame.Frame.FLAG_OP_RESPONSE;

public class RequestEpollChannel extends EpollChannel {

    public OpScheduler opScheduler;
    public RequestService requestService;
    public FrameAllocator requestFrameAllocator;
    public FrameAllocator remoteResponseFrameAllocator;
    private Frame inboundFrame;

    @Override
    public void onRead(ByteBuffer receiveBuffer) {
        Frame responseChain = null;
        for (; ; ) {
            if (inboundFrame == null) {
                if (receiveBuffer.remaining() < INT_SIZE_IN_BYTES + INT_SIZE_IN_BYTES) {
                    break;
                }

                int size = receiveBuffer.getInt();
                int flags = receiveBuffer.getInt();
                if ((flags & FLAG_OP_RESPONSE) == 0) {
                    inboundFrame = requestFrameAllocator.allocate(size);
                } else {
                    inboundFrame = remoteResponseFrameAllocator.allocate(size);
                }
                inboundFrame.byteBuffer().limit(size);
                inboundFrame.writeInt(size);
                inboundFrame.writeInt(flags);
                inboundFrame.channel = this;
            }

            int size = inboundFrame.size();
            int remaining = size - inboundFrame.position();
            inboundFrame.write(receiveBuffer, remaining);

            if (!inboundFrame.isComplete()) {
                break;
            }

            inboundFrame.complete();
            inboundFrame = null;
            framesRead.inc();

            if (inboundFrame.isFlagRaised(FLAG_OP_RESPONSE)) {
                inboundFrame.next = responseChain;
                responseChain = inboundFrame;
            } else {
                opScheduler.schedule(inboundFrame);
            }
        }

        if (responseChain != null) {
            requestService.handleResponse(responseChain);
        }
    }
}
