/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingsphere.shardingproxy.backend.jdbc.connection;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import io.shardingsphere.core.constant.ConnectionMode;
import io.shardingsphere.core.constant.transaction.TransactionType;
import io.shardingsphere.core.routing.router.masterslave.MasterVisitedManager;
import io.shardingsphere.shardingproxy.runtime.schema.LogicSchema;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Backend connection.
 *
 * @author zhaojun
 * @author zhangliang
 */
@Slf4j
@Getter
public final class BackendConnection implements AutoCloseable {
    
    private LogicSchema logicSchema;
    
    private final Multimap<String, Connection> cachedConnections = LinkedHashMultimap.create();
    
    private final Collection<Statement> cachedStatements = new CopyOnWriteArrayList<>();
    
    private final Collection<ResultSet> cachedResultSets = new CopyOnWriteArrayList<>();
    
    private final Collection<MethodInvocation> methodInvocations = new ArrayList<>();
    
    @Setter
    private ConnectionStatus status = ConnectionStatus.INIT;
    
    private TransactionType transactionType;
    
    public BackendConnection(final TransactionType transactionType) {
        this.transactionType = transactionType;
    }
    
    /**
     * Change transaction type of current channel.
     *
     * @param transactionType transaction type
     */
    public void setTransactionType(final TransactionType transactionType) {
        if (ConnectionStatus.TRANSACTION != status) {
            this.transactionType = transactionType;
        }
    }
    
    /**
     * Change logic schema of current channel.
     *
     * @param logicSchema logic schema
     */
    public void setLogicSchema(final LogicSchema logicSchema) {
        if (ConnectionStatus.TRANSACTION != status) {
            this.logicSchema = logicSchema;
        }
    }
    
    /**
     * Get connections of current thread datasource.
     *
     * @param connectionMode connection mode
     * @param dataSourceName data source name
     * @param connectionSize size of connections to be get
     * @return connection
     * @throws SQLException SQL exception
     */
    public List<Connection> getConnections(final ConnectionMode connectionMode, final String dataSourceName, final int connectionSize) throws SQLException {
        if (ConnectionStatus.INIT == status || ConnectionStatus.TERMINATED == status) {
            status = ConnectionStatus.RUNNING;
        }
        Collection<Connection> connections;
        synchronized (cachedConnections) {
            connections = cachedConnections.get(dataSourceName);
        }
        List<Connection> result;
        if (connections.size() >= connectionSize) {
            result = new ArrayList<>(connections).subList(0, connectionSize);
        } else if (!connections.isEmpty()) {
            result = new ArrayList<>(connectionSize);
            result.addAll(connections);
            List<Connection> newConnections = createNewConnections(connectionMode, dataSourceName, connectionSize - connections.size());
            result.addAll(newConnections);
            synchronized (cachedConnections) {
                cachedConnections.putAll(dataSourceName, newConnections);
            }
        } else {
            result = createNewConnections(connectionMode, dataSourceName, connectionSize);
            synchronized (cachedConnections) {
                cachedConnections.putAll(dataSourceName, result);
            }
        }
        return result;
    }
    
    private List<Connection> createNewConnections(final ConnectionMode connectionMode, final String dataSourceName, final int connectionSize) throws SQLException {
        List<Connection> result = logicSchema.getBackendDataSource().getConnections(connectionMode, dataSourceName, connectionSize);
        for (Connection each : result) {
            replayMethodsInvocation(each);
        }
        return result;
    }
    
    /**
     * Get connection size.
     *
     * @return connection size
     */
    public int getConnectionSize() {
        return cachedConnections.values().size();
    }
    
    /**
     * Add statement.
     *
     * @param statement statement to be added
     */
    public void add(final Statement statement) {
        cachedStatements.add(statement);
    }
    
    /**
     * Add result set.
     *
     * @param resultSet result set to be added
     */
    public void add(final ResultSet resultSet) {
        cachedResultSets.add(resultSet);
    }
    
    /**
     * Cancel statement.
     */
    public void cancel() {
        Collection<SQLException> exceptions = new LinkedList<>();
        for (Statement each : cachedStatements) {
            try {
                each.cancel();
            } catch (final SQLException ex) {
                exceptions.add(ex);
            }
        }
        if (!exceptions.isEmpty()) {
            log.warn("Failed to cancel statement due to {}", exceptions);
        }
        cachedStatements.clear();
    }
    
    @Override
    public void close() throws SQLException {
        close(false);
    }
    
    /**
     * Close cached connection.
     *
     * @param forceClose force close flag
     * @throws SQLException SQL exception
     */
    public void close(final boolean forceClose) throws SQLException {
        Collection<SQLException> exceptions = new LinkedList<>();
        MasterVisitedManager.clear();
        exceptions.addAll(closeStatements());
        exceptions.addAll(closeResultSets());
        if (ConnectionStatus.TRANSACTION != status || forceClose) {
            exceptions.addAll(releaseConnections(forceClose));
        }
        throwSQLExceptionIfNecessary(exceptions);
    }
    
    private Collection<SQLException> closeResultSets() {
        Collection<SQLException> result = new LinkedList<>();
        for (ResultSet each : cachedResultSets) {
            try {
                each.close();
            } catch (final SQLException ex) {
                result.add(ex);
            }
        }
        cachedResultSets.clear();
        return result;
    }
    
    private Collection<SQLException> closeStatements() {
        Collection<SQLException> result = new LinkedList<>();
        for (Statement each : cachedStatements) {
            try {
                each.close();
            } catch (final SQLException ex) {
                result.add(ex);
            }
        }
        cachedStatements.clear();
        return result;
    }
    
    Collection<SQLException> releaseConnections(final boolean forceRollback) {
        Collection<SQLException> result = new LinkedList<>();
        for (Connection each : cachedConnections.values()) {
            try {
                if (forceRollback && ConnectionStatus.TRANSACTION == status) {
                    each.rollback();
                }
                each.close();
            } catch (SQLException ex) {
                result.add(ex);
            }
        }
        cachedConnections.clear();
        methodInvocations.clear();
        return result;
    }
    
    private void throwSQLExceptionIfNecessary(final Collection<SQLException> exceptions) throws SQLException {
        if (exceptions.isEmpty()) {
            return;
        }
        SQLException ex = new SQLException();
        for (SQLException each : exceptions) {
            ex.setNextException(each);
        }
        throw ex;
    }
    
    private void replayMethodsInvocation(final Object target) {
        for (MethodInvocation each : methodInvocations) {
            each.invoke(target);
        }
    }
}
