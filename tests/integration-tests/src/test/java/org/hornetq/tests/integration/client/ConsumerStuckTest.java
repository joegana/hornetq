/*
 * Copyright 2009 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.hornetq.tests.integration.client;

import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.TransportConfiguration;
import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.api.core.client.ClientProducer;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.api.core.client.ClientSessionFactory;
import org.hornetq.api.core.client.HornetQClient;
import org.hornetq.api.core.client.ServerLocator;
import org.hornetq.core.client.impl.ClientSessionFactoryImpl;
import org.hornetq.core.protocol.core.impl.RemotingConnectionImpl;
import org.hornetq.core.remoting.impl.netty.NettyConnection;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.tests.util.ServiceTestBase;
import org.junit.Before;
import org.junit.Test;

/**
 * @author <a href="mailto:andy.taylor@jboss.org">Andy Taylor</a>
 * @author <a href="mailto:clebert.suconic@jboss.org">Clebert Suconic</a>
 */
public class ConsumerStuckTest extends ServiceTestBase
{
   private HornetQServer server;

   private final SimpleString QUEUE = new SimpleString("ConsumerTestQueue");

   protected boolean isNetty()
   {
      return true;
   }

   @Before
   @Override
   public void setUp() throws Exception
   {
      super.setUp();

      server = createServer(false, isNetty());

      server.start();
   }

   @Test
   public void testClientStuckTest() throws Exception
   {

      ServerLocator locator = HornetQClient.createServerLocatorWithoutHA(new TransportConfiguration(NETTY_CONNECTOR_FACTORY));
      locator.setConnectionTTL(1000);
      locator.setClientFailureCheckPeriod(100);
      locator.setConsumerWindowSize(10 * 1024 * 1024);
      ClientSessionFactory sf = locator.createSessionFactory();
      ((ClientSessionFactoryImpl) sf).stopPingingAfterOne();

      RemotingConnectionImpl remotingConnection = (RemotingConnectionImpl) sf.getConnection();
      ClientSession session = sf.createSession(false, true, true, true);

      session.createQueue(QUEUE, QUEUE, null, false);

      ClientProducer producer = session.createProducer(QUEUE);

      final int numMessages = 10000;

      for (int i = 0; i < numMessages; i++)
      {
         ClientMessage message = createTextMessage(session, "m" + i);
         producer.send(message);
      }


      final ClientConsumer consumer = session.createConsumer(QUEUE);
      session.start();

      final NettyConnection nettyConnection = (NettyConnection) remotingConnection.getTransportConnection();


      Thread tReceive = new Thread()
      {
         public void run()
         {
            boolean first = true;
            try
            {
               while (!Thread.interrupted())
               {
                  ClientMessage received = consumer.receive(500);
                  System.out.println("Received " + received);
                  if (first)
                  {
                     first = false;
                     nettyConnection.getNettyChannel().setReadable(false);
                  }
                  if (received != null)
                  {
                     received.acknowledge();
                  }
               }
            }
            catch (Throwable e)
            {
               Thread.currentThread().interrupt();
               e.printStackTrace();
            }
         }
      };

      tReceive.start();

      try
      {

         assertEquals(1, server.getSessions().size());

         System.out.println("sessions = " + server.getSessions().size());

         assertEquals(1, server.getConnectionCount());

         long timeout = System.currentTimeMillis() + 20000;

         while (System.currentTimeMillis() < timeout && server.getSessions().size() != 0)
         {
            Thread.sleep(10);
         }

         System.out.println("Size = " + server.getConnectionCount());

         System.out.println("sessions = " + server.getSessions().size());



         if (server.getSessions().size() != 0)
         {
            System.out.println(threadDump("Thread dump"));
            fail("The cleanup wasn't able to finish cleaning the session. It's probably stuck, look at the thread dump generated by the test for more information");
         }

         assertEquals(0, server.getConnectionCount());
      }
      finally
      {
         nettyConnection.getNettyChannel().setReadable(true);
         tReceive.interrupt();
         tReceive.join();
      }
   }

}
