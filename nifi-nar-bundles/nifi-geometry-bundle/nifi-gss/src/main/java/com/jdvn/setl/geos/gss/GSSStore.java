/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jdvn.setl.geos.gss;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.nifi.annotation.behavior.DynamicProperties;
import org.apache.nifi.annotation.behavior.DynamicProperty;
import org.apache.nifi.annotation.behavior.RequiresInstanceClassLoading;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.expression.AttributeExpression;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.reporting.InitializationException;

import com.cci.gss.jdbc.driver.IGSSConnection;

@Tags({ "GSS Store", "jdbc", "database", "connection", "pooling", "spatial data" })
@CapabilityDescription("GSS Store ControllerService implementation of GSSService.")
@DynamicProperties({
		@DynamicProperty(name = "JDBC property name", value = "JDBC property value", expressionLanguageScope = ExpressionLanguageScope.VARIABLE_REGISTRY, description = "JDBC driver property name and value applied to JDBC connections."),
		@DynamicProperty(name = "SENSITIVE.JDBC property name", value = "JDBC property value", expressionLanguageScope = ExpressionLanguageScope.NONE, description = "JDBC driver property name prefixed with 'SENSITIVE.' handled as a sensitive property.") })
@RequiresInstanceClassLoading
public class GSSStore extends AbstractControllerService implements GSSService {

	/** Property Name Prefix for Sensitive Dynamic Properties */
	protected static final String SENSITIVE_PROPERTY_PREFIX = "SENSITIVE.";

	private static final String DEFAULT_MIN_IDLE = "0";
	private static final String DEFAULT_MAX_IDLE = "8";
	private static final String DEFAULT_EVICTION_RUN_PERIOD = String.valueOf(-1L);
    private static final String DEFAULT_MIN_EVICTABLE_IDLE_TIME = "30 mins";
    private static final String DEFAULT_SOFT_MIN_EVICTABLE_IDLE_TIME = String.valueOf(-1L);

	public static final PropertyDescriptor DATABASE_URL = new PropertyDescriptor.Builder()
			.name("Database Connection URL")
			.description(
					"A database connection URL used to connect to a database. May contain database system name, host, port, database name and some parameters."
							+ " The exact syntax of a database connection URL is specified by your DBMS.")
			.defaultValue(null).addValidator(StandardValidators.NON_EMPTY_VALIDATOR).required(true)
			.expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY).build();

	public static final PropertyDescriptor DB_USER = new PropertyDescriptor.Builder().name("Database User")
			.description("Database user name").defaultValue(null).addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
			.expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY).build();

	public static final PropertyDescriptor DB_PASSWORD = new PropertyDescriptor.Builder().name("Password")
			.description("The password for the database user").defaultValue(null).required(false).sensitive(true)
			.addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
			.expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY).build();

	public static final PropertyDescriptor MAX_WAIT_TIME = new PropertyDescriptor.Builder().name("Max Wait Time")
			.description("The maximum amount of time that the pool will wait (when there are no available connections) "
					+ " for a connection to be returned before failing, or -1 to wait indefinitely. ")
			.defaultValue("500 millis").required(true).addValidator(DBCPValidator.CUSTOM_TIME_PERIOD_VALIDATOR)
			.expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY).sensitive(false).build();

	public static final PropertyDescriptor MAX_TOTAL_CONNECTIONS = new PropertyDescriptor.Builder()
			.name("Max Total Connections")
			.description(
					"The maximum number of active connections that can be allocated from this pool at the same time, "
							+ " or negative for no limit.")
			.defaultValue("64").required(true).addValidator(StandardValidators.INTEGER_VALIDATOR)
			.expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY).sensitive(false).build();
	
    public static final PropertyDescriptor EVICTION_RUN_PERIOD = new PropertyDescriptor.Builder()
            .displayName("Time Between Eviction Runs")
            .name("dbcp-time-between-eviction-runs")
            .description("The number of milliseconds to sleep between runs of the idle connection evictor thread. When " +
                    "non-positive, no idle connection evictor thread will be run.")
            .defaultValue(DEFAULT_EVICTION_RUN_PERIOD)
            .required(false)
            .addValidator(DBCPValidator.CUSTOM_TIME_PERIOD_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();
    public static final PropertyDescriptor MIN_EVICTABLE_IDLE_TIME = new PropertyDescriptor.Builder()
            .displayName("Minimum Evictable Idle Time")
            .name("dbcp-min-evictable-idle-time")
            .description("The minimum amount of time a connection may sit idle in the pool before it is eligible for eviction.")
            .defaultValue(DEFAULT_MIN_EVICTABLE_IDLE_TIME)
            .required(false)
            .addValidator(DBCPValidator.CUSTOM_TIME_PERIOD_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    public static final PropertyDescriptor SOFT_MIN_EVICTABLE_IDLE_TIME = new PropertyDescriptor.Builder()
            .displayName("Soft Minimum Evictable Idle Time")
            .name("dbcp-soft-min-evictable-idle-time")
            .description("The minimum amount of time a connection may sit idle in the pool before it is eligible for " +
                    "eviction by the idle connection evictor, with the extra condition that at least a minimum number of" +
                    " idle connections remain in the pool. When the not-soft version of this option is set to a positive" +
                    " value, it is examined first by the idle connection evictor: when idle connections are visited by " +
                    "the evictor, idle time is first compared against it (without considering the number of idle " +
                    "connections in the pool) and then against this soft option, including the minimum idle connections " +
                    "constraint.")
            .defaultValue(DEFAULT_SOFT_MIN_EVICTABLE_IDLE_TIME)
            .required(false)
            .addValidator(DBCPValidator.CUSTOM_TIME_PERIOD_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();
    
    
	public static final PropertyDescriptor MIN_IDLE = new PropertyDescriptor.Builder()
			.displayName("Minimum Idle Connections").name("dbcp-min-idle-conns")
			.description("The minimum number of connections that can remain idle in the pool, without extra ones being "
					+ "created, or zero to create none.")
			.defaultValue(DEFAULT_MIN_IDLE).required(false)
			.addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
			.expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY).build();

	public static final PropertyDescriptor MAX_IDLE = new PropertyDescriptor.Builder()
			.displayName("Max Idle Connections").name("dbcp-max-idle-conns")
			.description("The maximum number of connections that can remain idle in the pool, without extra ones being "
					+ "released, or negative for no limit.")
			.defaultValue(DEFAULT_MAX_IDLE).required(false).addValidator(StandardValidators.INTEGER_VALIDATOR)
			.expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY).build();

	public static final PropertyDescriptor ENCODING = new PropertyDescriptor.Builder()
			.name("Character Set")
			.description("The character set used for each connection, the default value is UTF-8.")
			.defaultValue("UTF-8")
			.required(true)
			.addValidator(StandardValidators.CHARACTER_SET_VALIDATOR)
			.expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
			.build();
	
	private static final List<PropertyDescriptor> properties;

	static {
		final List<PropertyDescriptor> props = new ArrayList<>();
		props.add(DATABASE_URL);
		props.add(DB_USER);
		props.add(DB_PASSWORD);
		props.add(ENCODING);
		props.add(MAX_WAIT_TIME);
		props.add(MAX_TOTAL_CONNECTIONS);
		props.add(MIN_IDLE);
		props.add(MAX_IDLE);
        props.add(EVICTION_RUN_PERIOD);
        props.add(MIN_EVICTABLE_IDLE_TIME);
        props.add(SOFT_MIN_EVICTABLE_IDLE_TIME);
		properties = Collections.unmodifiableList(props);
	}

	private DbmsType dbmsType;
	private String m_connectionURL;
	private String m_userName;
	private String m_password;
	private String m_encoding;

	private volatile GenericObjectPool<Connection> mConnectionPool;
	protected Map<String, Connection> mTxConnections = new HashMap<String, Connection>();

	protected boolean validateConnection(Connection connection) throws SQLException {
		return connection.isValid(0);
	}

	protected Connection createConnection() throws SQLException {
		
		Properties props = new Properties();
		props.setProperty("user", m_userName);
		props.setProperty("password", m_password);
		props.setProperty("encoding", m_encoding);
		IGSSConnection connection = (IGSSConnection) DriverManager.getConnection(m_connectionURL, props);
		if (dbmsType == null) {
			try {
				dbmsType = DbmsType.valueOf(connection.getProperty(PropertyConstants.GSS_DBMS_TYPE));

				if (dbmsType == null) {
					System.err.println(
							"The type of backend dbms can't be identified. It will be considered as an ORACLE.");
					dbmsType = DbmsType.oracle;
				}
			} catch (Throwable t) {
			}
		}
		return connection;
	}

	@Override
	protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
		return properties;
	}

	@Override
	protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(final String propertyDescriptorName) {
		final PropertyDescriptor.Builder builder = new PropertyDescriptor.Builder().name(propertyDescriptorName)
				.required(false).dynamic(true)
				.addValidator(StandardValidators
						.createAttributeExpressionLanguageValidator(AttributeExpression.ResultType.STRING, true))
				.addValidator(StandardValidators.ATTRIBUTE_KEY_PROPERTY_NAME_VALIDATOR);

		if (propertyDescriptorName.startsWith(SENSITIVE_PROPERTY_PREFIX)) {
			builder.sensitive(true).expressionLanguageSupported(ExpressionLanguageScope.NONE);
		} else {
			builder.expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY);
		}

		return builder.build();
	}

	@SuppressWarnings("deprecation")
	@OnEnabled
	public void onConfigured(final ConfigurationContext context) throws InitializationException {

		final String driverName  = "com.cci.gss.driver.GSSDriver";
		final String user        = context.getProperty(DB_USER).evaluateAttributeExpressions().getValue();
		final String passw       = context.getProperty(DB_PASSWORD).evaluateAttributeExpressions().getValue();
		final String dburl       = context.getProperty(DATABASE_URL).evaluateAttributeExpressions().getValue();
		final String encoding    = context.getProperty(ENCODING).evaluateAttributeExpressions().getValue();
		final Integer maxTotal   = context.getProperty(MAX_TOTAL_CONNECTIONS).evaluateAttributeExpressions().asInteger();
		final Long maxWaitMillis = extractMillisWithInfinite(context.getProperty(MAX_WAIT_TIME).evaluateAttributeExpressions());
		
		final Long timeBetweenEvictionRunsMillis = extractMillisWithInfinite(context.getProperty(EVICTION_RUN_PERIOD).evaluateAttributeExpressions());
        final Long minEvictableIdleTimeMillis = extractMillisWithInfinite(context.getProperty(MIN_EVICTABLE_IDLE_TIME).evaluateAttributeExpressions());
        final Long softMinEvictableIdleTimeMillis = extractMillisWithInfinite(context.getProperty(SOFT_MIN_EVICTABLE_IDLE_TIME).evaluateAttributeExpressions());
		
		final Integer minIdle = context.getProperty(MIN_IDLE).evaluateAttributeExpressions().asInteger();
		final Integer maxIdle = context.getProperty(MAX_IDLE).evaluateAttributeExpressions().asInteger();

		this.m_connectionURL = dburl;
		this.m_userName = user;
		this.m_password = passw;
		this.m_encoding = encoding;

		getDriver(driverName, dburl);

		// Init a connection pool
		mConnectionPool = new GenericObjectPool<Connection>(new BasePooledObjectFactory<Connection>() {
			public Connection create() throws Exception {
				return createConnection();
			}

			public PooledObject<Connection> wrap(Connection connection) {
				return new DefaultPooledObject<Connection>(connection);
			}

			public boolean validateObject(PooledObject<Connection> pooledConnection) {
				try {
					return validateConnection(pooledConnection.getObject());
				} catch (Throwable t) {
					return false;
				}
			}

			public void destroyObject(PooledObject<Connection> pooledConnection) throws Exception {
				pooledConnection.getObject().close();
			}
		});
		mConnectionPool.setMaxTotal(maxTotal);
		mConnectionPool.setBlockWhenExhausted(true);
		mConnectionPool.setMaxIdle(maxIdle);
		mConnectionPool.setMinIdle(minIdle);
		mConnectionPool.setMaxWaitMillis(maxWaitMillis);
		
		mConnectionPool.setTimeBetweenEvictionRunsMillis(timeBetweenEvictionRunsMillis);
		mConnectionPool.setMinEvictableIdleTimeMillis(minEvictableIdleTimeMillis);
		mConnectionPool.setSoftMinEvictableIdleTimeMillis(softMinEvictableIdleTimeMillis);
		
		mConnectionPool.setTestOnBorrow(true);
		mConnectionPool.setTestOnReturn(true);

		IGSSConnection connection = (IGSSConnection) getConnection(null);

		try {
			String dbmsTypeString = connection.getProperty(PropertyConstants.GSS_DBMS_TYPE);
			if (dbmsTypeString != null) {
				dbmsType = DbmsType.valueOf(dbmsTypeString);
			}
		} catch (Throwable t) {
		}

		if (dbmsType == null) {
			System.err.println("The type of backend dbms can't be identified. It will be considered as an ORACLE.");
			dbmsType = DbmsType.oracle;
		}

		System.out.println("Init Driver is OK");
		returnConnection(connection);

	}

	private Driver getDriver(final String driverName, final String url) {
		final Class<?> clazz;

		try {
			clazz = Class.forName(driverName);
		} catch (final ClassNotFoundException e) {
			throw new ProcessException("Driver class " + driverName + " is not found", e);
		}

		try {
			return DriverManager.getDriver(url);
		} catch (final SQLException e) {
			// In case the driver is not registered by the implementation, we explicitly try
			// to register it.
			try {
				final Driver driver = (Driver) clazz.newInstance();
				DriverManager.registerDriver(driver);
				return DriverManager.getDriver(url);
			} catch (final SQLException e2) {
				throw new ProcessException("No suitable driver for the given Database Connection URL", e2);
			} catch (final IllegalAccessException | InstantiationException e2) {
				throw new ProcessException("Creating driver instance is failed", e2);
			}
		}
	}

	private Long extractMillisWithInfinite(PropertyValue prop) {
		return "-1".equals(prop.getValue()) ? -1 : prop.asTimePeriod(TimeUnit.MILLISECONDS);
	}

	@OnDisabled
	public void shutdown() {
		mConnectionPool.close();
		for (Connection connection : mTxConnections.values()) {
			if (connection != null) {
				try {
					connection.close();
				} catch (Exception e) {
				}
			}
		}
	}

	@Override
	public IGSSConnection getConnection(String txName) {

		if (txName == null) {
			try {
				IGSSConnection conn = (IGSSConnection) mConnectionPool.borrowObject();
				return conn;
			} catch (Throwable t) {
				throw new ProcessException(t);
			}
		} else {
			txName = txName.toUpperCase();
			if (!mTxConnections.containsKey(txName)) {
				throw new ProcessException("Transaction '" + txName + "' is not found.");
			}

			return (IGSSConnection) mTxConnections.get(txName);
		}

	}

	@Override
	public void returnConnection(Connection connection) {
		if (connection == null) {
			return;
		}

		try {
			mConnectionPool.returnObject(connection);
		} catch (Throwable t) {
		}
	}

	@Override
	public boolean isWorkingWell() {
		Connection connection = null;
		try {
			connection = getConnection(null);
			return connection.isValid(10);
		} catch (Throwable t) {
			t.printStackTrace();
			return false;
		} finally {
			returnConnection(connection);
		}
	}

	@Override
	public String toString() {
		return "GSSStoreService[id=" + getIdentifier() + "]";
	}

	public DbmsType getBackendDBMSType() {
		return dbmsType;
	}
	public String getEncoding() {
		return m_encoding;
	}
	@Override
	public IGSSConnection getConnection() throws ProcessException {
		return getConnection(null);
	}

	@Override
	public boolean hasTransaction(String txName) {
		return mTxConnections.containsKey(txName.toUpperCase());
	}

	@Override
	public void enableTransaction(boolean enable, String txName) {
		if (txName == null) {
			throw new ProcessException("Transaction name is null.");
		}

		txName = txName.toUpperCase();

		if (enable) {
			if (mTxConnections.containsKey(txName)) {
				throw new ProcessException("Transaction '" + txName + "' is already initialized.");
			}

			try {
				Connection connection = createConnection();
				mTxConnections.put(txName, connection);

				connection.setAutoCommit(false);
			} catch (SQLException e) {
				throw new ProcessException("Failed to create an exclusive connection for Transaction '" + txName + "'.",
						e);
			}
		} else {
			if (!mTxConnections.containsKey(txName)) {
				throw new ProcessException("Transaction '" + txName + "' is not found.");
			}

			Connection connection = mTxConnections.remove(txName);
			try {
				connection.setAutoCommit(true);
			} catch (SQLException e) {
				throw new ProcessException(e);
			} finally {
				if (connection != null) {
					try {
						connection.close();
					} catch (Exception e) {
					}
				}
			}
		}

	}

	@Override
	public void commit(String txName) {

		if (txName == null) {
			throw new ProcessException("Transaction name is null.");
		}

		txName = txName.toUpperCase();
		if (!mTxConnections.containsKey(txName)) {
			throw new ProcessException("Transaction '" + txName + "' is not found.");
		}

		Connection connection = mTxConnections.get(txName);
		try {
			connection.commit();
		} catch (SQLException e) {
			throw new ProcessException(e);
		}

	}

	@Override
	public void rollback(String txName) {
		if (txName == null) {
			throw new ProcessException("Transaction name is null.");
		}

		txName = txName.toUpperCase();
		if (!mTxConnections.containsKey(txName)) {
			throw new ProcessException("Transaction '" + txName + "' is not found.");
		}

		Connection connection = mTxConnections.get(txName);
		try {
			connection.rollback();
		} catch (SQLException e) {
			throw new ProcessException(e);
		}

	}

}
