package io.netty.incubator.channel.uring;

import com.hazelcast.spi.impl.reactor.Channel;
import com.hazelcast.spi.impl.reactor.CircularQueue;
import com.hazelcast.spi.impl.reactor.Frame;
import io.netty.buffer.ByteBuf;
import io.netty.channel.unix.IovArray;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class IO_UringChannel extends Channel {
    protected LinuxSocket socket;
    protected IO_UringReactor reactor;

    // ======================================================
    // For the reading side of the channel
    // ======================================================
    protected ByteBuffer readBuffer;
    protected ByteBuf receiveBuff;
    protected Frame inboundFrame;

    // ======================================================
    // for the writing side of the channel.
    // ======================================================
    // concurrent state
    protected AtomicBoolean flushed = new AtomicBoolean(false);
    protected final ConcurrentLinkedQueue<Frame> unflushedFrames = new ConcurrentLinkedQueue<>();
    // isolated state.
    public IovArray iovArray;
    protected CircularQueue<Frame> flushedFrames = new CircularQueue<>(16);

    @Override
    public void flush() {
        if (!flushed.get() && flushed.compareAndSet(false, true)) {
            int remaining = flushedFrames.remaining();

            for (int k = 0; k < remaining; k++) {
                Frame frame = unflushedFrames.poll();
                boolean offered = flushedFrames.offer(frame);
                assert offered;
            }

            System.out.println("Flush: scheduled was false");

            reactor.schedule(this);
        } else {
            System.out.println("Flush: scheduled was true");
        }
    }

    // called by the Reactor.
    public boolean resetFlushed() {
        if(!unflushedFrames.isEmpty() || !flushedFrames.isEmpty()){
            return false;
        }

        flushed.set(false);

        if(unflushedFrames.isEmpty()){
           return true;
        }

        if (flushed.compareAndSet(false, true)) {
            reactor.schedule(this);
            return false;
        }else{
            return true;
        }
    }

    @Override
    public void write(Frame frame) {
        if (Thread.currentThread() == reactor) {
            if (!flushedFrames.offer(frame)) {
                unflushedFrames.add(frame);
            }
        } else {
            unflushedFrames.add(frame);
        }
    }

    @Override
    public void writeAndFlush(Frame frame) {
        unflushedFrames.add(frame);
        flush();
    }
}
