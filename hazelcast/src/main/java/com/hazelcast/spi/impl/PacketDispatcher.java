/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.spi.impl;

import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Connection;
import com.hazelcast.nio.EndpointManager;
import com.hazelcast.nio.Packet;
import com.hazelcast.util.function.Consumer;

import static com.hazelcast.instance.OutOfMemoryErrorDispatcher.inspectOutOfMemoryError;
import static com.hazelcast.nio.Packet.FLAG_OP_CONTROL;
import static com.hazelcast.nio.Packet.FLAG_OP_RESPONSE;

/**
 * A {@link Consumer} that dispatches the {@link Packet} to the right service. For example, operations are sent to the
 * {@link com.hazelcast.spi.OperationService}, events are sent to the {@link com.hazelcast.spi.EventService} etc.
 */
public final class PacketDispatcher implements Consumer<Packet> {

    private final ILogger logger;
    private final Consumer<Packet> eventService;
    private final Consumer<Packet> operationExecutor;
    private final Consumer<Packet> jetPacketConsumer;
    private final Consumer<Packet> responseHandler;
    private final Consumer<Packet> invocationMonitor;

    public PacketDispatcher(ILogger logger,
                            Consumer<Packet> operationExecutor,
                            Consumer<Packet> responseHandler,
                            Consumer<Packet> invocationMonitor,
                            Consumer<Packet> eventService,
                            Consumer<Packet> jetPacketConsumer) {
        this.logger = logger;
        this.responseHandler = responseHandler;
        this.eventService = eventService;
        this.invocationMonitor = invocationMonitor;
        this.operationExecutor = operationExecutor;
        this.jetPacketConsumer = jetPacketConsumer;
    }

    @Override
    public void accept(Packet packet) {
        try {
            switch (packet.getPacketType()) {
                case OPERATION:
                    if (packet.isFlagRaised(FLAG_OP_RESPONSE)) {
                        responseHandler.accept(packet);
                    } else if (packet.isFlagRaised(FLAG_OP_CONTROL)) {
                        invocationMonitor.accept(packet);
                    } else {
                        operationExecutor.accept(packet);
                    }
                    break;
                case EVENT:
                    eventService.accept(packet);
                    break;
                case BIND:
                case EXTENDED_BIND:
                    Connection connection = packet.getConn();
                    EndpointManager endpointManager = connection.getEndpointManager();
                    endpointManager.accept(packet);
                    break;
                case JET:
                    jetPacketConsumer.accept(packet);
                    break;
                default:
                    logger.severe("Header flags [" + Integer.toBinaryString(packet.getFlags())
                            + "] specify an undefined packet type " + packet.getPacketType().name());
            }
        } catch (Throwable t) {
            inspectOutOfMemoryError(t);
            logger.severe("Failed to process: " + packet, t);
        }
    }
}
