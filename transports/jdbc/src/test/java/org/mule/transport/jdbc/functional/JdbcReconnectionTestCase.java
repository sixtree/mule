/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.transport.jdbc.functional;


import static org.junit.Assert.assertTrue;
import org.mule.api.MuleEventContext;
import org.mule.context.notification.ConnectionNotification;
import org.mule.tck.functional.EventCallback;
import org.mule.tck.junit4.FunctionalTestCase;
import org.mule.tck.listener.ConnectionListener;
import org.mule.tck.util.MuleDerbyTestUtils;
import org.mule.transport.jdbc.JdbcConnector;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.dbutils.QueryRunner;
import org.junit.AfterClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class JdbcReconnectionTestCase extends FunctionalTestCase
{

    private String configFile;

    public JdbcReconnectionTestCase(String configFile)
    {
        setStartContext(false);
        this.configFile = configFile;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> parameters()
    {
        return Arrays.asList(new Object[] {"jdbc-reconnection-blocking-config.xml"},
                             new Object[] {"jdbc-reconnection-nonblocking-config.xml"});
    }

    @Override
    protected String getConfigFile()
    {
        return configFile;
    }

    @AfterClass
    public static void stopDatabase() throws SQLException
    {
        MuleDerbyTestUtils.stopDatabase();
    }

    @Test
    public void reconnectsAfterConnectException() throws Exception
    {
        final CountDownLatch messageReceivedLatch = new CountDownLatch(1);
        final CountDownLatch connectFailedLatch = new CountDownLatch(3);
        final CountDownLatch reconnectedLatch = new CountDownLatch(1);

        getFunctionalTestComponent("test").setEventCallback(new EventCallback()
        {
            @Override
            public void eventReceived(MuleEventContext context, Object component) throws Exception
            {
                messageReceivedLatch.countDown();
            }
        });

        ConnectionListener connectionListener = new ConnectionListener(muleContext)
                .setExpectedAction(ConnectionNotification.CONNECTION_FAILED).setNumberOfExecutionsRequired(3);

        // Load data so that messages are received in the test flow.
        MuleDerbyTestUtils.defaultDerbyCleanAndInit("derby.properties", "database.name");
        initializeDatabase();

        muleContext.start();

        // Wait until one message is successfully received.
        assertTrue("No message received", messageReceivedLatch.await(LOCK_TIMEOUT, TimeUnit.MILLISECONDS));

        // Stop the database, Mule should try to reconnect
        stopDatabase();

        // Wait for reconnect attempts ("connect failed" notifications)
        connectionListener.waitUntilNotificationsAreReceived();

        getFunctionalTestComponent("test").setEventCallback(new EventCallback()
        {
            @Override
            public void eventReceived(MuleEventContext context, Object component) throws Exception
            {
                reconnectedLatch.countDown();
            }
        });

        // Restart the database
        MuleDerbyTestUtils.defaultDerbyCleanAndInit("derby.properties", "database.name");
        initializeDatabase();

        // Wait for a message to arrive
        assertTrue("Reconnection failed", reconnectedLatch.await(LOCK_TIMEOUT, TimeUnit.MILLISECONDS));

        stopDatabase();
    }


    /**
     * Creates a test table and inserts a row in it.
     */
    private void initializeDatabase() throws Exception
    {
        JdbcConnector jdbcConnector = (JdbcConnector) muleContext.getRegistry().lookupConnector("jdbcConnector");

        QueryRunner qr = jdbcConnector.getQueryRunner();
        qr.update(jdbcConnector.getConnection(), "CREATE TABLE TEST(ID INTEGER GENERATED BY DEFAULT AS IDENTITY(START WITH 0) NOT NULL PRIMARY KEY, DATA VARCHAR(255))");
        qr.update(jdbcConnector.getConnection(), "INSERT INTO TEST(DATA) VALUES ('a')");
    }

}
