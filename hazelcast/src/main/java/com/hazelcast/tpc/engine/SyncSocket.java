package com.hazelcast.tpc.engine;

import com.hazelcast.internal.util.counters.SwCounter;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.tpc.engine.frame.Frame;

import java.io.Closeable;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hazelcast.internal.util.counters.SwCounter.newSwCounter;


/**
 * This class is not thread-safe.
 *
 * The SyncSocket is blocking; so therefor should not be used inside an {@link Eventloop}.
 */
public abstract class SyncSocket implements Closeable {

    protected final ILogger logger = Logger.getLogger(getClass());
    protected final AtomicBoolean closed = new AtomicBoolean();

    protected volatile SocketAddress remoteAddress;
    protected volatile SocketAddress localAddress;

    protected final SwCounter framesWritten = newSwCounter();

    protected final SwCounter framesRead = newSwCounter();

    protected final SwCounter bytesRead = newSwCounter();

    protected final SwCounter bytesWritten = newSwCounter();

    public long framesWritten() {
        return framesWritten.get();
    }

    public long bytesRead() {
        return bytesRead.get();
    }

    public long bytesWritten() {
        return bytesWritten.get();
    }

    public long framesRead() {
        return framesRead.get();
    }

    /**
     * Returns the remote address.
     *
     * If the AsyncSocket isn't connected, null is returned.
     *
     * This method is thread-safe.
     *
     * @return the remote address.
     */
    public final SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * Returns the local address.
     *
     * If the AsyncSocket isn't connected, null is returned.
     *
     * This method is thread-safe.
     *
     * @return the local address.
     */
    public final SocketAddress getLocalAddress() {
        return localAddress;
    }

    public abstract void setSoLinger(int soLinger);

    public abstract int getSoLinger();

    public abstract void setKeepAlive(boolean keepAlive);

    public abstract boolean isKeepAlive();

    public abstract void setTcpNoDelay(boolean tcpNoDelay);

    public abstract boolean isTcpNoDelay();

    public abstract void setReceiveBufferSize(int size);

    public abstract int getReceiveBufferSize();

    public abstract void setSendBufferSize(int size);

    public abstract int getSendBufferSize();

    public abstract Frame read();

    public abstract Frame tryRead();

    /**
     * Ensures that any scheduled frames are flushed to the socket.
     *
     * What happens under the hood is that the AsyncSocket is scheduled in the
     * {@link Eventloop} where at some point in the future the frames get written
     * to the socket.
     *
     * This method is thread-safe.
     */
    public abstract void flush();

    /**
     * Writes a frame to the AsyncSocket with scheduling the AsyncSocket
     * in the eventloop.
     *
     * This call can be used to buffer a series of request and then call
     * {@link #flush()}.
     *
     * This method is thread-safe.
     *
     * There is no guarantee that frame is actually going to be received by the caller if
     * the AsyncSocket has accepted the frame. E.g. when the connection closes.
     *
     * @param frame the frame to write.
     * @return true if the frame was accepted, false if there was an overload.
     */
    public abstract boolean write(Frame frame);

    /**
     * Writes a frame and flushes it.
     *
     * This is the same as calling {@link #write(Frame)} followed by a {@link #flush()}.
     *
     * There is no guarantee that frame is actually going to be received by the caller if
     * the AsyncSocket has accepted the frame. E.g. when the connection closes.
     *
     * This method is thread-safe.
     *
     * @param frame the frame to write.
     * @return true if the frame was accepted, false if there was an overload.
     */
    public abstract boolean writeAndFlush(Frame frame);

    /**
     * Connects asynchronously to some address.
     *
     * @param address the address to connect to.
     * @return a {@link CompletableFuture}
     */
    public abstract void connect(SocketAddress address);

    /**
     * Closes this {@link AsyncSocket}.
     *
     * This method is thread-safe.
     *
     * If the AsyncSocket is already closed, the call is ignored.
     */
    public abstract void close();

    /**
     * Checks if this AsyncSocket is closed.
     *
     * This method is thread-safe.
     *
     * @return true if closed, false otherwise.
     */
    public final boolean isClosed() {
        return closed.get();
    }

    @Override
    public final String toString() {
        return getClass().getSimpleName() + "[" + localAddress + "->" + remoteAddress + "]";
    }
}

