package com.hazelcast.spi.impl.reactor;

import com.hazelcast.internal.nio.Connection;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hazelcast.internal.nio.Bits.INT_SIZE_IN_BYTES;
import static com.hazelcast.internal.nio.Bits.LONG_SIZE_IN_BYTES;


// always
// size: int
// flags: int
// partitionId: int  : 8

// request
// callid: long: 12
// opcode: 20

// response
// call id: long 12
public class Frame {

    public static final int FLAG_OP = 1 << 1;
    public static final int FLAG_OP_RESPONSE = 2 << 1;

    public CompletableFuture future;
    public Frame next;
    public Connection connection;
    public Channel channel;

    public static final int OFFSET_SIZE = 0;
    public static final int OFFSET_FLAGS = OFFSET_SIZE + INT_SIZE_IN_BYTES;
    public static final int OFFSET_PARTITION_ID = OFFSET_FLAGS + INT_SIZE_IN_BYTES;

    public static final int OFFSET_REQUEST_CALL_ID = OFFSET_PARTITION_ID + INT_SIZE_IN_BYTES;
    public static final int OFFSET_REQUEST_OPCODE = OFFSET_REQUEST_CALL_ID + LONG_SIZE_IN_BYTES;
    public static final int OFFSET_REQUEST_PAYLOAD = OFFSET_REQUEST_OPCODE + INT_SIZE_IN_BYTES;

    public static final int OFFSET_RESPONSE_CALL_ID = OFFSET_PARTITION_ID + INT_SIZE_IN_BYTES;
    public static final int OFFSET_RESPONSE_PAYLOAD = OFFSET_RESPONSE_CALL_ID + LONG_SIZE_IN_BYTES;

    public boolean trackRelease;
    private ByteBuffer buff;
    public FrameAllocator allocator;
    public boolean concurrent = false;

    // make field?
    protected AtomicInteger refCount = new AtomicInteger();

    public Frame() {
    }

    public Frame(int size) {
        this.buff = ByteBuffer.allocate(size);
    }

    public Frame(ByteBuffer buffer) {
        this.buff = buffer;
    }

    public Frame newFuture() {
        this.future = new CompletableFuture();
        return this;
    }

    public void clear() {
        buff.clear();
    }

    public Frame writeRequestHeader(int partitionId, int opcode) {
        buff.putInt(-1); //size
        buff.putInt(Frame.FLAG_OP);
        buff.putInt(partitionId);
        buff.putLong(-1); //callid
        buff.putInt(opcode);
        return this;
    }

    public Frame writeResponseHeader(int partitionId, long callId) {
        buff.putInt(-1);  //size
        buff.putInt(Frame.FLAG_OP_RESPONSE);
        buff.putInt(partitionId);
        buff.putLong(callId);
        return this;
    }

    public ByteBuffer byteBuffer() {
        return buff;
    }

    public int size() {
        if (buff.limit() < INT_SIZE_IN_BYTES) {
            return -1;
        }
        return buff.getInt(0);
    }

    public void setSize(int size) {
        buff.putInt(0, size);
    }

    public void init(int capacity) {
        this.buff = ByteBuffer.allocate(capacity);
    }

    public Frame writeByte(byte value) {
        buff.put(value);
        return this;
    }

    public Frame writeChar(char value) {
        buff.putChar(value);
        return this;
    }

    public void setInt(int pos, int value) {
        buff.putInt(pos, value);
    }

    public Frame writeInt(int value) {
        buff.putInt(value);
        return this;
    }

    public int position() {
        return buff.position();
    }

    public void putLong(int index, long value) {
        buff.putLong(index, value);
    }

    public long getLong(int index) {
        return buff.getLong(index);
    }

    // very inefficient
    public Frame writeString(String s) {
        buff.putInt(s.length());
        for (int k = 0; k < s.length(); k++) {
            buff.putChar(s.charAt(k));
        }
        return this;
    }

    // very inefficient
    public void readString(StringBuffer sb) {
        int size = buff.getInt();
        for (int k = 0; k < size; k++) {
            sb.append(buff.getChar());
        }
    }

    public Frame writeLong(long value) {
        buff.putLong(value);
        return this;
    }

    public int readInt() {
        return buff.getInt();
    }

    public long readLong() {
        return buff.getLong();
    }

    public char readChar() {
        return buff.getChar();
    }

    public boolean isComplete() {
        if (buff.position() < INT_SIZE_IN_BYTES) {
            // not enough bytes.
            return false;
        } else {
            return buff.position() == buff.getInt(0);
        }
    }

    public Frame complete() {
        buff.flip();
        return this;
    }

    public Frame completeWriting() {
        buff.putInt(OFFSET_SIZE, buff.position());
        buff.flip();
        return this;
    }

    public int getInt(int index) {
        return buff.getInt(index);
    }

    public boolean isFlagRaised(int flag) {
        int flags = buff.getInt(OFFSET_FLAGS);
        return (flags & flag) != 0;
    }

    public Frame write(ByteBuffer src, int count) {
        if (src.remaining() <= count) {
            buff.put(src);
        } else {
            int limit = src.limit();
            src.limit(src.position() + count);
            buff.put(src);
            src.limit(limit);
        }
        return this;
    }

    public Frame position(int position) {
        buff.position(position);
        return this;
    }

    public int remaining() {
        return buff.remaining();
    }

    public int refCount() {
        return refCount.get();
    }

    public void acquire() {
        if (allocator == null) {
            return;
        }

        if (!concurrent) {
            refCount.lazySet(refCount.get() + 1);
        } else {
            for (; ; ) {
                int current = refCount.get();
                if (current == 0) {
                    throw new IllegalStateException("Can't acquire a freed frame");
                }

                if (refCount.compareAndSet(current, current + 1)) {
                    break;
                }
            }
        }
    }

    public void release() {
        if (allocator == null) {
            return;
        }

        if (!concurrent) {
            int current = refCount.get();
            if (current == 1) {
                refCount.lazySet(0);
                allocator.free(this);
            } else if (current <= 0) {
                throw new IllegalStateException("Too many releases. Ref counter must be larger than 0, current:" + current);
            } else {
                refCount.lazySet(current - 1);
            }
        } else {
            for (; ; ) {
                int current = refCount.get();
                if (current <= 0) {
                    throw new IllegalStateException("Too many releases. Ref counter must be larger than 0, current:" + current);
                }
                if (refCount.compareAndSet(current, current - 1)) {
                    if (current == 1) {
                        allocator.free(this);
                    }
                    break;
                }
            }
        }
    }
}
