package com.hazelcast.table;

import com.hazelcast.internal.nio.Bits;
import com.hazelcast.internal.nio.Packet;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.internal.serialization.impl.ByteArrayObjectDataOutput;
import com.hazelcast.internal.util.HashUtil;
import com.hazelcast.spi.impl.AbstractDistributedObject;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.spi.impl.reactor.OpCodes;
import com.hazelcast.spi.impl.reactor.ReactorFrontEnd;
import com.hazelcast.spi.impl.reactor.Invocation;
import com.hazelcast.spi.tenantcontrol.DestroyEventContext;
import com.hazelcast.table.impl.TableService;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.util.concurrent.TimeUnit.SECONDS;

public class TableProxy<K, V> extends AbstractDistributedObject implements Table<K, V> {

    private final ReactorFrontEnd reactorFrontEnd;
    private final String name;
    private final InternalSerializationService ss;
    private final int partitionCount;

    public TableProxy(NodeEngineImpl nodeEngine, TableService tableService, String name) {
        super(nodeEngine, tableService);
        this.reactorFrontEnd = nodeEngine.getReactorFrontEnd();
        this.name = name;
        this.ss = (InternalSerializationService) nodeEngine.getSerializationService();
        this.partitionCount = nodeEngine.getPartitionService().getPartitionCount();
    }


    @Override
    public void upsert(V v) {
        CompletableFuture f = asyncUpsert(v);
        try {
            f.get(23, SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private CompletableFuture asyncUpsert(V v) {
        Item item = (Item)v;
        Invocation inv = new Invocation();
        inv.opcode = OpCodes.TABLE_UPSERT;
        inv.partitionId = HashUtil.hashToIndex(Long.hashCode(item.key), partitionCount);
        inv.out = new ByteArrayObjectDataOutput(128, ss, BIG_ENDIAN);
        inv.out.position(Packet.DATA_OFFSET);

        try {
            inv.out.writeByte(OpCodes.TABLE_UPSERT);
            inv.out.position(inv.out.position() + Bits.LONG_SIZE_IN_BYTES);
            inv.out.writeInt(name.length());
            for (int k = 0; k < name.length(); k++) {
                inv.out.writeChar(name.charAt(k));
            }
            inv.out.writeLong(item.key);
            inv.out.writeInt(item.a);
            inv.out.writeInt(item.b);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return reactorFrontEnd.invoke(inv);
    }

    @Override
    public void concurrentNoop(int concurrency) {
        CompletableFuture[] futures = new CompletableFuture[concurrency];
        for (int k = 0; k < futures.length; k++) {
            futures[k] = asyncNoop();
        }

        for (CompletableFuture f : futures) {
            try {
                f.get(23, SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void noop() {
        CompletableFuture f = asyncNoop();
        try {
            f.get(23, SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private CompletableFuture asyncNoop() {
        Invocation inv = new Invocation();
        inv.opcode = OpCodes.TABLE_NOOP;
        inv.partitionId = ThreadLocalRandom.current().nextInt(partitionCount);
        inv.out = new ByteArrayObjectDataOutput(128, ss, BIG_ENDIAN);
        inv.out.position(Packet.DATA_OFFSET);

        try {
            inv.out.writeByte(OpCodes.TABLE_NOOP);
            inv.out.position(inv.out.position() + Bits.LONG_SIZE_IN_BYTES);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return reactorFrontEnd.invoke(inv);
    }


    // better pipelining support
    @Override
    public void upsertAll(V[] values) {
        CompletableFuture[] futures = new CompletableFuture[values.length];
        for (int k = 0; k < futures.length; k++) {
            futures[k] = asyncUpsert(values[k]);
        }

        for (CompletableFuture f : futures) {
            try {
                f.get(23, SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void selectByKey(K key) {
        Invocation request = new Invocation();
        request.opcode = OpCodes.TABLE_SELECT_BY_KEY;
        request.partitionId = 0;
        CompletableFuture f = reactorFrontEnd.invoke(request);
        f.join();
    }

    @Override
    public String getName() {
        return name;
    }

    @NotNull
    @Override
    public DestroyEventContext getDestroyContextForTenant() {
        return super.getDestroyContextForTenant();
    }

    @Override
    public String getServiceName() {
        return TableService.SERVICE_NAME;
    }

}
