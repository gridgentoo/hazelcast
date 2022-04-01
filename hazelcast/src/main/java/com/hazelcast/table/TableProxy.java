package com.hazelcast.table;

import com.hazelcast.internal.nio.Bits;
import com.hazelcast.internal.nio.Packet;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.internal.serialization.impl.ByteArrayObjectDataOutput;
import com.hazelcast.spi.impl.AbstractDistributedObject;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.spi.impl.reactor.OpCodes;
import com.hazelcast.spi.impl.reactor.ReactorFrontEnd;
import com.hazelcast.spi.impl.reactor.Request;
import com.hazelcast.spi.tenantcontrol.DestroyEventContext;
import com.hazelcast.table.impl.TableService;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.nio.ByteOrder.BIG_ENDIAN;

public class TableProxy<K, V> extends AbstractDistributedObject implements Table<K, V> {

    private final ReactorFrontEnd reactorFrontEnd;
    private final String name;
    private final InternalSerializationService ss;

    public TableProxy(NodeEngineImpl nodeEngine, TableService tableService, String name) {
        super(nodeEngine, tableService);
        this.reactorFrontEnd = nodeEngine.getReactorFrontEnd();
        this.name = name;
        this.ss = (InternalSerializationService) nodeEngine.getSerializationService();
    }

    @Override
    public void upsert(V item) {
        Request request = new Request();
        request.opcode = OpCodes.TABLE_UPSERT;
        request.partitionId = ThreadLocalRandom.current().nextInt(271);
        request.out = new ByteArrayObjectDataOutput(128, ss, BIG_ENDIAN);
        request.out.position(Packet.DATA_OFFSET);

        try {
            request.out.writeByte(OpCodes.TABLE_UPSERT);
            request.out.position(request.out.position() + Bits.LONG_SIZE_IN_BYTES);
            request.out.writeInt(name.length());
            for (int k = 0; k < name.length(); k++) {
                request.out.writeChar(name.charAt(k));
            }
        }catch (IOException e){
            throw new RuntimeException(e);
        }
        CompletableFuture f = reactorFrontEnd.invoke(request);
        try {
            f.get(23, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void selectByKey(K key) {
        Request request = new Request();
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
