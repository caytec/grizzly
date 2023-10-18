/*
 * Copyright (c) 2010, 2020 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0, which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.glassfish.grizzly.samples.simpleauth;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;

/**
 * Server authentication filter, which intercepts client<->server communication. Filter checks, if coming message is
 * authentication request, sent by client. If yes - the filter generated client id and sends the authentication reponse
 * to a client. If incoming message is not authentication request - filter checks whether client connection has been
 * authenticated. If yes - filter removes client authentication header ("auth-id: <connection-id>") from a message and
 * pass control to a next filter in a chain, otherwise - throws an Exception.
 *
 * @author Alexey Stashok
 */
public class ServerAuthFilter extends BaseFilter {

    // Authenticated clients connection map
    private final Map<Connection, String> authenticatedConnections = new ConcurrentHashMap<>();

    // Random, to generate client ids.
    private final Random random = new SecureRandom();

    /**
     * The method is called once we have received {@link MultiLinePacket} from a client. Filter check if incoming message is
     * the client authentication request. If yes - we generate new client id and send it back in the authentication
     * response. If the message is not authentication request - we check message authentication header to correspond to a
     * connection id in the authenticated clients map. If it's ok - the filter removes authentication header from the
     * message and pass the message to a next filter in a filter chain, otherwise, if authentication failed - the filter
     * throws an Exception
     *
     * @param ctx Request processing context
     *
     * @return {@link NextAction}
     * @throws IOException
     */
    @Override
    public NextAction handleRead(FilterChainContext ctx) throws IOException {
        // Get the connection
        final Connection connection = ctx.getConnection();
        // Get the incoming packet
        final MultiLinePacket packet = ctx.getMessage();

        // get the command string
        final String command = packet.getLines().get(0);
        // check if it's authentication request from a client
        if (command.startsWith("authentication-request")) {
            // if yes - authenticate the client
            MultiLinePacket authResponse = authenticate(connection);
            // send authentication response back
            ctx.write(authResponse);

            // stop the packet processing
            return ctx.getStopAction();
        } else {
            // if it's some custom message
            // Get id line
            final String idLine = packet.getLines().get(1);

            // Check the client id
            if (checkAuth(connection, idLine)) {
                // if id corresponds to what server has -
                // Remove authentication header
                packet.getLines().remove(1);

                // Pass to a next filter
                return ctx.getInvokeAction();
            } else {
                // if authentication failed - throw an Exception.
                throw new IllegalStateException("Client is not authenticated!");
            }
        }
    }

    /**
     * The method is called each time, when server sends a message to a client. First of all filter check if this packet is
     * not authentication-response. If yes - filter just passes control to a next filter in a chain, if not - filter gets
     * the client id from its local authenticated clients map and adds "auth-id: <connection-id>" header to the outgoing
     * message and finally passes control to a next filter in a chain.
     *
     * @param ctx Response processing context
     *
     * @return {@link NextAction}
     * @throws IOException
     */
    @Override
    public NextAction handleWrite(FilterChainContext ctx) throws IOException {

        // Get the connection
        final Connection connection = ctx.getConnection();
        // Get the sending packet
        final MultiLinePacket packet = ctx.getMessage();

        // Get the message command
        final String command = packet.getLines().get(0);

        // if it's authentication-response
        if (command.equals("authentication-response")) {
            // just pass control to a next filter in a chain
            return ctx.getInvokeAction();
        } else {
            // if not - get connection id from authenticated connections map
            final String id = authenticatedConnections.get(connection);
            if (id != null) {
                // if id exists - add "auth-id" header to a packet
                packet.getLines().add(1, "auth-id: " + id);
                // pass control to a next filter in a chain
                return ctx.getInvokeAction();
            }

            // connection id wasn't found in a map of authenticated connections
            // throw an Exception
            throw new IllegalStateException("Client is not authenticated");
        }
    }

    /**
     * The method generates the key and builds the authentication response packet.
     *
     * @param connection the {@link Connection}
     * @return authentication reponse packet
     */
    private MultiLinePacket authenticate(Connection connection) {
        // Generate the key
        String id = String.valueOf(System.currentTimeMillis() ^ random.nextLong());
        // put it to the authenticated connection map
        authenticatedConnections.put(connection, id);

        // Build authentication response packet
        final MultiLinePacket response = MultiLinePacket.create();
        response.getLines().add("authentication-response");
        response.getLines().add("auth-id: " + id);

        return response;
    }

    /**
     * Method checks, whether authentication header, sent in the message corresponds to a value, stored in the server
     * authentication map.
     *
     * @param connection {@link Connection}
     * @param idLine authentication header string.
     *
     * @return <tt>true</tt>, if authentication passed, or <tt>false</tt> otherwise.
     */
    private boolean checkAuth(Connection connection, String idLine) {
        // Get the connection id, from the server map
        final String registeredId = authenticatedConnections.get(connection);
        if (registeredId == null) {
            return false;
        }

        if (idLine.startsWith("auth-id:")) {
            // extract client id from the authentication header
            String id = getId(idLine);
            // check whether extracted id is equal to what server has in his map
            return registeredId.equals(id);
        } else {
            return false;
        }
    }

    /**
     * The method is called, when a connection gets closed. We remove connection entry in authenticated connections map.
     *
     * @param ctx Request processing context
     *
     * @return {@link NextAction}
     * @throws IOException
     */
    @Override
    public NextAction handleClose(FilterChainContext ctx) throws IOException {
        authenticatedConnections.remove(ctx.getConnection());

        return ctx.getInvokeAction();
    }

    /**
     * Retrieve connection id from a packet header
     *
     * @param idLine header, which looks like "auth-id: <connection-id>".
     * @return connection id
     */
    private String getId(String idLine) {
        return idLine.split(":")[1].trim();
    }
}
